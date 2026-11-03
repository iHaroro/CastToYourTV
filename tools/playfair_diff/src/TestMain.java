import com.github.serezhka.jap2lib.RefImpl;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

/** 双实现对拍：随机 keymsg/ekey，比较我们 Playfair 与 serezhka 参考实现的输出 */
public class TestMain {
    public static void main(String[] args) throws Exception {
        byte[] blob = Files.readAllBytes(Paths.get(args[0]));
        ours.PlayfairTables.init(blob);

        Random rnd = new Random(12345);
        int fail = 0;
        for (int trial = 0; trial < 200; trial++) {
            byte[] keymsg = new byte[164];
            rnd.nextBytes(keymsg);
            keymsg[0] = 'F'; keymsg[1] = 'P'; keymsg[2] = 'L'; keymsg[3] = 'Y';
            keymsg[4] = 3;
            keymsg[12] = (byte) (1 + rnd.nextInt(3)); // mode 1..3
            byte[] ekey = new byte[72];
            rnd.nextBytes(ekey);

            byte[] a = ours.Playfair.decrypt(keymsg, ekey);
            byte[] b = RefImpl.decryptAesKey(keymsg, ekey);
            if (!Arrays.equals(a, b)) {
                fail++;
                if (fail <= 3) {
                    System.out.println("MISMATCH trial=" + trial + " mode=" + keymsg[12]);
                    System.out.println("  ours=" + hex(a));
                    System.out.println("  ref =" + hex(b));
                }
            }
        }
        System.out.println(fail == 0 ? "ALL 200 TRIALS MATCH" : "FAILURES: " + fail + "/200");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
