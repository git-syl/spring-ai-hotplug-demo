package lab;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class Lab {

    public static void main(String[] args) throws Exception {
        URL[] pluginPath = {Path.of(args[0]).toUri().toURL()};
        ClassLoader host = Lab.class.getClassLoader();

        // 实验 1：JDK 内置的三层加载器
        System.out.println("1) String 的加载器: " + String.class.getClassLoader());
        System.out.println("   java.sql.Driver 的加载器: " + java.sql.Driver.class.getClassLoader().getName());
        System.out.println("   Lab 的加载器: " + host.getName() + "，它的父加载器: " + host.getParent().getName());

        try (URLClassLoader a = new URLClassLoader("plugin-a", pluginPath, host);
             URLClassLoader b = new URLClassLoader("plugin-b", pluginPath, host)) {

            // 实验 2：加载不等于初始化
            Class<?> helperA = a.loadClass("lab.plugin.Helper");
            System.out.println("2) loadClass 已返回: " + helperA.getName());
            Class.forName("lab.plugin.Helper", true, a);
            System.out.println("   forName(..., true, a) 已返回");

            // 实验 3：父优先，契约接口由宿主提供
            System.out.println("3) a 加载的 lab.Greeter 就是宿主的 Greeter: " + (a.loadClass("lab.Greeter") == Greeter.class));

            // 实验 4：类的身份 = 全限定名 + 定义它的加载器
            Class<?> helperB = b.loadClass("lab.plugin.Helper");
            System.out.println("4) 名字相同: " + helperA.getName().equals(helperB.getName())
                    + "，是同一个 Class: " + (helperA == helperB));
            Object fromB = helperB.getConstructor().newInstance();
            try {
                helperA.cast(fromB);
            } catch (ClassCastException e) {
                System.out.println("   ClassCastException: " + e.getMessage());
            }

            // 实验 4b：插件自带一份契约，又按"子优先"加载，宿主的强制转换就会失败
            try (URLClassLoader childFirst = new ChildFirstLoader("plugin-child-first", pluginPath, host)) {
                Object greeter = childFirst.loadClass("lab.plugin.HelloGreeter").getConstructor().newInstance();
                try {
                    Greeter g = (Greeter) greeter;
                    System.out.println("4b) 转换成功: " + g.greet("x"));
                } catch (ClassCastException e) {
                    System.out.println("4b) ClassCastException: " + e.getMessage());
                }
            }

            // 实验 5：线程上下文类加载器（TCCL）与 ServiceLoader
            System.out.println("5) 默认 TCCL 下找到的 Greeter 实现: " + ServiceLoader.load(Greeter.class).stream().count() + " 个");
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(a);
            try {
                for (Greeter greeter : ServiceLoader.load(Greeter.class)) {
                    System.out.println("   切换 TCCL 后: " + greeter.greet("Spring AI"));
                }
            } finally {
                thread.setContextClassLoader(previous);
            }
        }

        // 实验 6：类加载器何时能被回收
        System.out.println("6) 没有任何残留引用: 被回收 = " + collected(loadAndDrop(pluginPath, host, null)));
        List<Object> registry = new ArrayList<>(); // 模拟宿主里的一个全局缓存
        WeakReference<ClassLoader> leaked = loadAndDrop(pluginPath, host, registry);
        System.out.println("   宿主集合还引用着插件对象: 被回收 = " + collected(leaked));
        registry.clear();
        System.out.println("   清空集合之后: 被回收 = " + collected(leaked));
    }

    /** 创建加载器、实例化一个插件对象、关闭加载器，只把弱引用带出方法。 */
    private static WeakReference<ClassLoader> loadAndDrop(URL[] path, ClassLoader host, List<Object> registry)
            throws Exception {
        URLClassLoader loader = new URLClassLoader("plugin-c", path, host);
        Object greeter = loader.loadClass("lab.plugin.HelloGreeter").getConstructor().newInstance();
        if (registry != null) registry.add(greeter);
        loader.close();
        return new WeakReference<>(loader);
    }

    /** 教学用的"子优先"加载器：除 java.* 以外，先在自己的路径里找，找不到再交给父加载器。 */
    static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(String name, URL[] urls, ClassLoader parent) {
            super(name, urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null && !name.startsWith("java.")) {
                    try {
                        type = findClass(name);
                    } catch (ClassNotFoundException notMine) {
                        // 自己的路径里没有，下面交给父加载器
                    }
                }
                if (type == null) type = super.loadClass(name, false);
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }

    private static boolean collected(WeakReference<?> reference) throws InterruptedException {
        for (int i = 0; i < 20 && reference.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        return reference.get() == null;
    }
}
