package lab.plugin;

import lab.Greeter;

/** 插件里的实现，通过 META-INF/services/lab.Greeter 声明给 ServiceLoader。 */
public class HelloGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name + "（来自 " + getClass().getClassLoader().getName() + "）";
    }
}
