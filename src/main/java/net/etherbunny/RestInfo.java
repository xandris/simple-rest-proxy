package net.etherbunny;

import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.ObjIntConsumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.ws.rs.Consumes;
import javax.ws.rs.CookieParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.MatrixParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;

import net.etherbunny.RestInfo.BoundMethod.InvocationCtx;

public class RestInfo {
    private static final Logger LOG = Logger.getLogger(RestInfo.class.getName());
    
    public static <T> T buildProxy(Class<T> t, String basePath) {
        RestInfo info = new RestInfo(t);
        return (T) Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[]{t}, info.new RestInvoker(basePath));
    }

    public static <T> T buildProxy(Class<T> t, SubResourceInvocationCtx tmp) {
        RestInfo info = new RestInfo(t);
        return (T) Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[]{t}, info.new RestInvoker(tmp));
    }
    
    private final Class<?> clazz;
    private final List<BoundMethod> boundMethods;
    private final Map<Method, BoundMethod> boundMethodByOrig;
    private final Optional<Produces> produces;
    
    private RestInfo(Class<?> t) {
        clazz = t;
        boundMethods =
            stream(t.getMethods()).map(BoundMethod::new).filter(BoundMethod::isRestMethod).collect(toList());
        boundMethodByOrig = boundMethods.stream().collect(Collectors.toMap(BoundMethod::getMethod, m->m));
        produces = ofNullable(clazz.getAnnotation(Produces.class));
    }
    
    public WebTarget append(WebTarget webTarget) {
        if(clazz.isAnnotationPresent(Path.class)) {
            return webTarget.path(clazz.getAnnotation(Path.class).value());
        }
        return webTarget;
    }
    
    static class SubResourceInvocationCtx {
        protected WebTarget t;
        protected final Map<String, Object> paths;
        protected final MultivaluedMap<String, Object> queries;
        protected final MultivaluedMap<String, Object> matrices;
        protected final MultivaluedMap<String, Object> headers;
        protected final MultivaluedMap<String, String> forms;
        protected final MultivaluedMap<String, Object> cookies;

        public SubResourceInvocationCtx(SubResourceInvocationCtx ctx) {
            t = ctx.t;
            paths = new HashMap<>(ctx.paths);
            queries = new MultivaluedHashMap<>(ctx.queries);
            matrices = new MultivaluedHashMap<>(ctx.matrices);
            headers = new MultivaluedHashMap<>(ctx.headers);
            forms = new MultivaluedHashMap<>(ctx.forms);
            cookies = new MultivaluedHashMap<>(ctx.cookies);
        }
        
        public SubResourceInvocationCtx(WebTarget t) {
            requireNonNull(t);
            this.t = t;
            paths = new HashMap<>();
            queries = new MultivaluedHashMap<>();
            matrices = new MultivaluedHashMap<>();
            headers = new MultivaluedHashMap<>();
            forms = new MultivaluedHashMap<>();
            cookies = new MultivaluedHashMap<>();
        }
        
        public void apply(Class<?> c) {
            apply(c.getAnnotation(Path.class));
        }
        
        public void apply(Method m) {
            apply(m.getAnnotation(Path.class));
        }
        
        public void apply(Path p) {
            t = p == null ? t : t.path(p.value());
        }
        
        public void apply(PathParam p, Object value) {
            Object old = paths.put(p.value(), value);
            if(old != null) {
                LOG.warning("Conflicting values for " + p + ": '" + value + "' and '" + old + "'");
            }
        }

        public void apply(QueryParam p, Object value) {
            queries.add(p.value(), value);
        }

        public void apply(MatrixParam p, Object value) {
            matrices.add(p.value(), value);
        }

        public void apply(HeaderParam p, Object value) {
            headers.add(p.value(), value);
        }

        public void apply(FormParam p, Object value) {
            forms.add(p.value(), value.toString());
        }

        public void apply(CookieParam p, Object value) {
            cookies.add(p.value(), value);
        }
        
        public void entity(Method m, Object e) {
            // Shouldn't get here
            throw new IllegalArgumentException();
        }
        
    }

    static interface BoundParam {
        abstract public void apply(SubResourceInvocationCtx ctx, Object value);
    }
    
    private static final List<Class<? extends Annotation>> PARAMETER_ANNOTATION_TYPES = Arrays.asList(
            PathParam.class,
            QueryParam.class,
            MatrixParam.class,
            HeaderParam.class,
            FormParam.class,
            CookieParam.class
    );

    class BoundMethod {
        class InvocationCtx extends SubResourceInvocationCtx {
            private Optional<Entity<?>> e;
            
            public InvocationCtx(SubResourceInvocationCtx ctx) {
                super(ctx);
                e = Optional.empty();
            }

            public InvocationCtx(WebTarget t) {
                super(t);
                e = Optional.empty();
            }

            public Invocation resolve() {
                Optional<Entity<?>> entity = this.e;
                WebTarget t = this.t;
                t = t.resolveTemplates(paths);
                for(Map.Entry<String, List<Object>> e: queries.entrySet()) {
                    t = t.queryParam(e.getKey(), e.getValue().toArray());
                }

                for(Map.Entry<String, List<Object>> e: matrices.entrySet()) {
                    t = t.matrixParam(e.getKey(), e.getValue().toArray());
                }
                
                if(!forms.isEmpty()) {
                    if(entity.isPresent()) {
                        throw new IllegalArgumentException("Can't specify form params and entity param");
                    }
                    Form f = new Form(forms);
                    entity = Optional.of(Entity.form(f));
                }
                
                Builder r = t.request(getProduces());

                for(Map.Entry<String, List<Object>> e: cookies.entrySet()) {
                    e.getValue().forEach(v->r.cookie(e.getKey(), v.toString()));
                }
                
                r.headers(headers);
                
                return r.build(httpMethod.map(HttpMethod::value).orElse("GET"), entity.orElse(null));
            }
        }
        private final Method method;
        private final Optional<HttpMethod> httpMethod;
        private final List<BoundParam> params;
        private final GenericType<?> type;
        private final boolean isSubResourceLocator;
        private final Optional<Produces> produces;
        private final Optional<Consumes> consumes = Optional.empty();

        BoundMethod(Method method) {
            this.method = method;
            this.type = new GenericType<>(method.getGenericReturnType());
            httpMethod = findHttpMethod();
            isSubResourceLocator = httpMethod == null;
            produces = ofNullable(method.getAnnotation(Produces.class));
            AtomicReference<BoundParam> entityParam = new AtomicReference<>();
            params = stream(method.getParameters()).map(methodParam->{
                AtomicReference<Annotation> annotation = new AtomicReference<>();
                PARAMETER_ANNOTATION_TYPES.stream()
                    .map(c->methodParam.getAnnotation(c))
                    .filter(a->a!=null)
                    .forEach(a->{
                        Annotation o = annotation.getAndSet(a);
                        if(o != null) {
                            throw new IllegalArgumentException(MessageFormat.format(
                                    "Method {0} has both {1} and {2} annotations, and only one is allowed.",
                                    method.getName(), o.toString(), a.toString()));
                        }
                    });
                Annotation a = annotation.get();
                switch(a == null ? "" : a.annotationType().getSimpleName()) {
                case "PathParam":
                    return (BoundParam)((ctx,value)->ctx.apply((PathParam)a, value));
                case "QueryParam":
                    return (ctx,value)->ctx.apply((QueryParam)a,value);
                case "FormParam":
                    return (ctx,value)->ctx.apply((FormParam)a,value);
                case "MatrixParam":
                    return (ctx,value)->ctx.apply((MatrixParam)a,value);
                case "CookieParam":
                    return (ctx,value)->ctx.apply((CookieParam)a,value);
                case "HeaderParam":
                    return (ctx,value)->ctx.apply((HeaderParam)a,value);
                default: {
                    if(this.isSubResourceLocator) {
                        throw new IllegalArgumentException("Entity params are not allowed on sub-resource locator " + method.getName() + " (3.4.1)");
                    }
                    BoundParam p = (ctx,value)->ctx.entity(method, value);
                    if(entityParam.compareAndSet(null, p)) {
                        throw new IllegalArgumentException("Too many entity params on method " + method.getName() + " (3.3.2.1)");
                    }
                    return p;
                } }
            }).collect(Collectors.toList());
        }
        
        public String[] getProduces() {
            return (produces.isPresent() ? produces : RestInfo.this.produces).map(Produces::value).orElseGet(()->new String[0]);
        }

        public Method getMethod() {
            return method;
        }
        
        protected boolean isRestMethod() {
            return true;
        }
        
        private Optional<HttpMethod> findHttpMethod() {
            return stream(method.getAnnotations())
                    .map(a->a.annotationType().getAnnotation(HttpMethod.class))
                    .filter(a->a!=null).findAny();
        }
        
        public List<BoundParam> getParams() {
            return params;
        }

        public GenericType<?> getType() {
            return type;
        }

        public Optional<HttpMethod> getHttpMethod() {
            return httpMethod;
        }
    }
    
    public <T,U> U foldLeft(Stream<T> s, U acc, BiFunction<U, T, U> fn) {
        for(Iterator<T> i = s.iterator(); i.hasNext();) {
            acc = fn.apply(acc, i.next());
        }
        return acc;
    }
    
    public static class IntZippedStream<T> {
        private final Stream<T> ts;
        private final IntStream us;
        
        public IntZippedStream(Stream<T> t, IntStream u) {
            this.ts = t;
            this.us = u;
        }
        
        public void forEach(ObjIntConsumer<T> fn) {
            Iterator<T> t = ts.iterator();
            PrimitiveIterator.OfInt u = us.iterator();
            while(t.hasNext() && u.hasNext()) {
                fn.accept(t.next(), u.next());
            }
        }
    }
    
    public static class ZippedStream<T,U> {
        private final Stream<T> ts;
        private final Stream<U> us;
        
        public ZippedStream(Stream<T> t, Stream<U> u) {
            this.ts = t;
            this.us = u;
        }
        
        public void forEach(BiConsumer<T, U> fn) {
            Iterator<T> t = ts.iterator();
            Iterator<U> u = us.iterator();
            while(t.hasNext() && u.hasNext()) {
                fn.accept(t.next(), u.next());
            }
        }
    }
    
    public <T,U> ZippedStream<T,U> zip(Stream<T> t, Stream<U> u) {
        return new ZippedStream<>(t, u);
    }
    
    public <T> IntZippedStream<T> zip(Stream<T> t, IntStream u) {
        return new IntZippedStream<>(t, u);
    }
    
    public <T> IntZippedStream<T> zipWithIndexes(Stream<T> t) {
        return zip(t, IntStream.range(0, Integer.MAX_VALUE));
    }

    class RestInvoker implements InvocationHandler {
        final SubResourceInvocationCtx ctx;
        
        public RestInvoker(String baseUri) {
            Client client = ClientBuilder.newClient();
            ctx = new SubResourceInvocationCtx(client.target(baseUri));
            ctx.apply(clazz);
        }
        
        public RestInvoker(SubResourceInvocationCtx ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            BoundMethod bm = boundMethodByOrig.get(method);
            if(bm == null) {
                throw new IllegalArgumentException();
            }
            SubResourceInvocationCtx tmp = bm.isSubResourceLocator ? new SubResourceInvocationCtx(ctx) : bm.new InvocationCtx(ctx);
            zip(bm.getParams().stream(), stream(args)).forEach((a,b)->a.apply(tmp, b));
            if(bm.isSubResourceLocator) {
                return RestInfo.buildProxy(method.getReturnType(), tmp);
            } else {
                return ((InvocationCtx)tmp).resolve().invoke(method.getReturnType());
            }
        }
    }
    
}
