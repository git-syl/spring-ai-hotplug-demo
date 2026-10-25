public class InitQuiz {
    static class A { static { System.out.println("  A 初始化"); } static final String CONST = "常量"; }
    static class B { static { System.out.println("  B 初始化"); } }
    static class C { static { System.out.println("  C 初始化"); } static int counter = 1; }
    static class D { static { System.out.println("  D 初始化"); } static void hello() { } }
    static class E { static { System.out.println("  E 初始化"); } }
    static class F { static { System.out.println("  F 初始化"); } }
    static class Parent { static { System.out.println("  Parent 初始化"); } static int shared = 1; }
    static class Child extends Parent { static { System.out.println("  Child 初始化"); } }
    static class G { static { System.out.println("  G 初始化"); } }

    public static void main(String[] args) throws Exception {
        System.out.println("1. 读取编译期常量 A.CONST:");
        String s = A.CONST;
        System.out.println("2. 类字面量 B.class:");
        Class<?> b = B.class;
        System.out.println("3. 创建数组 new B[3]:");
        Object arr = new B[3];
        System.out.println("4. 读取非常量静态字段 C.counter:");
        int n = C.counter;
        System.out.println("5. 调用静态方法 D.hello():");
        D.hello();
        System.out.println("6. ClassLoader.loadClass(E):");
        InitQuiz.class.getClassLoader().loadClass("InitQuiz$E");
        System.out.println("7. Class.forName(E, false, loader):");
        Class.forName("InitQuiz$E", false, InitQuiz.class.getClassLoader());
        System.out.println("8. Class.forName(F)（单参数版本）:");
        Class.forName("InitQuiz$F");
        System.out.println("9. 通过子类读取父类静态字段 Child.shared:");
        int m = Child.shared;
        System.out.println("10. new G():");
        new G();
    }
}
