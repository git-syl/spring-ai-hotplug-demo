package lab.plugin;

public class Helper {
    static {
        System.out.println("   [Helper 的 static 块执行，加载器 = " + Helper.class.getClassLoader().getName() + "]");
    }
}
