package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

class ManagedTypeUsageTest {
    private static final String SYSTEM = """
            @fr.thegamerfarmer.jype.annotations.TypeSystem({
                @fr.thegamerfarmer.jype.annotations.TypeDef(name="VALUE", bound=Number.class)
            }) package usage;
            """;

    @Test
    void discoversTypesInsideSwitchArmsAndInitializerLambdas() throws Exception {
        JypeTestCompiler.compileAndRun(Map.of(
                "usage/package-info.java", SYSTEM,
                "usage/Holder.java", """
                        package usage;
                        public class Holder {
                            int fromSwitch(int branch, Object input) {
                                return switch (branch) {
                                    case 1 -> { VALUE value = (VALUE) input; yield value.intValue(); }
                                    default -> 0;
                                };
                            }
                            int fromLambda(Object input) {
                                java.util.function.Function<Object, Integer> convert = item -> {
                                    VALUE value = (VALUE) item;
                                    return value.intValue();
                                };
                                return convert.apply(input);
                            }
                            public static void main(String[] args) {
                                Holder<Integer> holder = new Holder<>();
                                if (holder.fromSwitch(1, 42) != 42 || holder.fromLambda(7) != 7)
                                    throw new AssertionError("method-body type usage was lost");
                            }
                        }
                        """), "usage.Holder");
    }

    @Test
    void handlesAnonymousBodiesArrayWildcardsAndAnnotatedTypePositions() throws Exception {
        JypeTestCompiler.compileAndRun(Map.of(
                "usage/package-info.java", SYSTEM,
                "usage/Holder.java", """
                        package usage;
                        import java.lang.annotation.*;
                        public class Holder {
                            @Target(ElementType.TYPE_USE) @interface Mark {}
                            int read(Object input) {
                                java.util.function.IntSupplier supplier = new java.util.function.IntSupplier() {
                                    public int getAsInt() {
                                        @Mark VALUE value = (@Mark VALUE) input;
                                        java.util.List<? extends VALUE> values = java.util.List.of(value);
                                        return values.get(0).intValue();
                                    }
                                };
                                return supplier.getAsInt();
                            }
                            public static void main(String[] args) {
                                if (new Holder<Integer>().read(19) != 19) throw new AssertionError();
                            }
                        }
                        """), "usage.Holder");
    }

    @Test
    void methodOwnedManagedNamesAndQualifiedTypesDoNotParameterizeTheClass() throws Exception {
        JypeTestCompiler.compileAndRun(Map.of(
                "usage/package-info.java", SYSTEM,
                "other/VALUE.java", "package other; public class VALUE {}",
                "usage/Holder.java", """
                        package usage;
                        public class Holder {
                            other.VALUE field;
                            static <VALUE> VALUE identity(VALUE input) { return input; }
                            static String local() {
                                class Local<VALUE> {
                                    VALUE value;
                                    Local(VALUE value) { this.value = value; }
                                    VALUE get() { return value; }
                                }
                                return new Local<String>("local").get();
                            }
                            static Object anonymous() {
                                return new Object() {
                                    <VALUE> VALUE identity(VALUE input) { return input; }
                                };
                            }
                            public static void main(String[] args) {
                                if (!"ok".equals(identity("ok"))) throw new AssertionError();
                                if (!"local".equals(local())) throw new AssertionError("local scope changed");
                                if (Holder.class.getTypeParameters().length != 0)
                                    throw new AssertionError("a method-owned name escaped into the class");
                                if (anonymous() == null) throw new AssertionError();
                            }
                        }
                        """), "usage.Holder");
    }

    @Test
    void managedNamesInMethodBoundsAreClassParameters() throws Exception {
        JypeTestCompiler.compileAndRun(Map.of(
                "usage/package-info.java", SYSTEM,
                "usage/Holder.java", """
                        package usage;
                        public class Holder {
                            <T extends VALUE> int read(T value) { return value.intValue(); }
                            public static void main(String[] args) {
                                if (new Holder<Integer>().read(23) != 23) throw new AssertionError();
                            }
                        }
                        """), "usage.Holder");
    }
}
