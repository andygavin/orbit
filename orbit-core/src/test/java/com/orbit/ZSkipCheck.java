package com.orbit;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks what actually happens for the \z failing lines.
 */
class ZSkipCheck {
    // Known failing line numbers
    static final int[] Z_LINES = {726, 735, 744, 753, 798, 807, 852, 861};

    @Test void checkAllZLines() throws Exception {
        InputStream is = getClass().getResourceAsStream("/perl-tests/re_tests");
        assertNotNull(is);
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.ISO_8859_1));
        String line;
        int lineNum = 0;
        boolean seenEnd = false;
        List<int[]> targets = new ArrayList<>();
        for (int t : Z_LINES) targets.add(new int[]{t, 0});

        while ((line = r.readLine()) != null) {
            lineNum++;
            if ("__END__".equals(line.trim())) { seenEnd = true; continue; }
            if (!seenEnd) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int finalLineNum = lineNum;
            for (int[] target : targets) {
                if (target[0] == finalLineNum) {
                    String[] cols = line.split("\t", 7);
                    String patCol = cols[0];
                    String subjCol = cols[1];

                    // Pattern: Form A
                    String inner = patCol.replace("\\n", "\n");
                    // Subject: interpolate
                    String subject = subjCol.replace("\\n", "\n").replace("\\t", "\t");

                    System.out.printf("Line %d: pat=%s subject=%s%n",
                        finalLineNum, repr(inner), repr(subject));

                    Pattern p = Pattern.compile(inner);
                    Matcher m = p.matcher(subject);
                    boolean found = m.find();
                    System.out.printf("  find()=%b hint=%s%n", found, p.prog().metadata.hint());
                    target[1] = found ? 1 : -1;
                }
            }
        }
        r.close();

        for (int[] t : targets) {
            System.out.printf("Line %d: %s%n", t[0], t[1] > 0 ? "PASS" : "FAIL");
        }
    }

    static String repr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\\') sb.append("\\\\");
            else sb.append(c);
        }
        return sb.append("\"").toString();
    }
}
