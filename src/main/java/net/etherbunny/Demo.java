package net.etherbunny;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

public class Demo {
    interface Dummy {
        @GET
        @Path("hello")
        void hello();
        
        @GET
        @Path("world/{name}")
        void world(@PathParam("name") String name);
        
        @Path("what")
        Dummy2 what();
    }
    
    interface Dummy2 {
        @GET
        @Path("the")
        void the();
    }

    public static void main(String[] args) {
        // TODO Auto-generated method stub
        Dummy d = RestInfo.buildProxy(Dummy.class, "http://localhost:8080/api");
        d.what().the();
    }

}
