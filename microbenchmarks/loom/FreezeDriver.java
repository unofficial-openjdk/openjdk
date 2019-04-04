import org.openjdk.benchmarks.cont.Freeze;

public class FreezeDriver {
    public static void main(String[] args) {
        Freeze bm = new Freeze();
        bm.paramCount = 3;
        bm.stackDepth = 5;

        int n = 10_000_000;

        System.out.println("Running " + n + " iterations");
        bm.setup();
        for (int i=0; i<n; i++) {
            bm.justYield();
        }
        System.out.println("Done");
    }
}
