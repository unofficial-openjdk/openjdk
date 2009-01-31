/*
 *  @test
 *  @bug 6592792
 *  @summary Add com.sun.xml.internal to the "package.access" property in $JAVA_HOME/lib/security/java.security
 *  @run shell Test6592792.sh
 */

import java.lang.*;
import java.lang.reflect.*;
import com.sun.xml.internal.ws.server.*;
import com.sun.xml.internal.ws.server.SingletonResolver;
import com.sun.xml.internal.ws.api.server.*;

public class Test {

  public static void main(String[] args) throws Exception{
      // Enable the security manager
      SecurityManager sm = new SecurityManager();
      System.setSecurityManager(sm);
      new Test();
  }

  Object invokeMethod(Object target,Method m,Object args[]) throws Exception {
      SingletonResolver r = new SingletonResolver(target);
      Invoker invoker = r.createInvoker();
      return invoker.invoke(null, m, args);
  }

  public Test() throws Exception{
      try {
          Class c=Class.forName("java.lang.Class");

          Class ctab[]=new Class[1];
          ctab[0]=Class.forName("java.lang.String");
          Method forName=c.getMethod("forName",ctab);

          Class gtab[]=new Class[2];
          gtab[0]=Class.forName("java.lang.String");
          gtab[1]=Class[].class;
          Method getMethod=c.getMethod("getMethod",gtab);

          Method newInstance=c.getMethod("newInstance",(Class[])null);

          Object otab[]=new Object[1];
          otab[0]="sun.misc.Unsafe";

          Object o=invokeMethod(null,forName,otab);
          c = (Class)o;		// sun.misc.Unsafe class
          // Test FAILED: Should n't have got the reference.   
          throw new RuntimeException("Test Failed: Got reference to: "+o);


          //o=invokeMethod(c,getMethod, new Object[]{"getUnsafe", (Class[])null});
          //System.out.println("Got reference to: "+o);
          //throw new RuntimeException("Got reference to: "+o);
          //o=invokeMethod(c,(Method)o,null);
          //System.out.println("Got reference to: "+o);
          //throw new RuntimeException("Got reference to: "+o);
   
      } catch(java.security.AccessControlException e) {
          System.out.println("Test passed");
          //e.printStackTrace();
      } 
   }
}
