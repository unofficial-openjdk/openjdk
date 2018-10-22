import org.openjdk.benchmarks.cont.Thaw;

public class ThawDriver {
    public static void main(String[] args) {
        Thaw bm = new Thaw();
        bm.paramCount = 3;
        bm.stackDepth = 5;

        int n = 1000_000;

        System.out.println("Running " + n + " iterations");
        bm.setup();
        for (int i=0; i<n; i++) {
            bm.justContinue();
        }
        System.out.println("Done");
    }
}