package lab;

/** 宿主和插件共享的契约：只放在宿主一侧，插件编译时依赖它、运行时由宿主提供。 */
public interface Greeter {
    String greet(String name);
}
