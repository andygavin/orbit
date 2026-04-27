package com.orbit;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact reproduction of what PerlRegexCompatTest does for line 726.
 */
class ZExact726 {
    @Test void exactLine726() throws Exception {
        // Read line 726 from the actual re_tests file, exactly as PerlRegexCompatTest does
        InputStream is = getClass().getResourceAsStream("/perl-tests/re_tests");
        assertNotNull(is, "re_tests not found");
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.ISO_8859_1));
        String line;
        int lineNum = 0;
        boolean seenEnd = false;
        String target726 = null;
        while ((line = r.readLine()) != null) {
            lineNum++;
            if ("__END__".equals(line.trim())) { seenEnd = true; continue; }
            if (!seenEnd) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (lineNum == 726) { target726 = line; break; }
        }
        r.close();
        assertNotNull(target726, "line 726 not found");
        System.out.println("Raw line 726: " + repr(target726));

        // Parse columns
        String[] cols = target726.split("\t", 7);
        System.out.println("cols[0] (pattern): " + repr(cols[0]));
        System.out.println("cols[1] (subject): " + repr(cols[1]));
        System.out.println("cols[2] (result): " + cols[2]);

        // Apply substitutions to pattern
        String patCol = cols[0]; // applyVarSubstitutions is a no-op for this
        // Form A (bare): replace \\n with \n
        String inner = patCol.replace("\\n", "\n");
        System.out.println("inner (compiled pattern string): " + repr(inner));

        // Apply interpolation to subject
        String subjectRaw = cols[1];
        // interpolate: \n -> newline, etc.
        String subject = subjectRaw.replace("\\n", "\n")
                                   .replace("\\t", "\t")
                                   .replace("\\r", "\r");
        System.out.println("subject (after interpolation): " + repr(subject));
        System.out.println("subject.length(): " + subject.length());

        Pattern p = Pattern.compile(inner);
        System.out.println("hint: " + p.prog().metadata.hint());
        Matcher m = p.matcher(subject);
        boolean found = m.find();
        System.out.println("find() = " + found);
        assertTrue(found, "a\\z should match in b\na");
    }

    static String repr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\\') sb.append("\\\\");
            else sb.append(c);
        }
        return sb.append("\"").toString();
    }
}
