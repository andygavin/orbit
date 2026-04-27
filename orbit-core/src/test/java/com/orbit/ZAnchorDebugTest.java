package com.orbit;

import com.orbit.api.Pattern;
import com.orbit.api.Matcher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZAnchorDebugTest {
    @Test
    void debugZAnchor() {
        // Exactly replicate line 726 processing
        String patCol = "a\\z"; // literal: a, backslash, z (3 chars)
        String inner = patCol.replace("\\n", "\n");
        System.out.println("inner length: " + inner.length() + " = " + repr(inner));
        
        String subject = "b\na";
        System.out.println("subject length: " + subject.length() + " = " + repr(subject));
        
        Pattern p = Pattern.compile(inner);
        System.out.println("hint: " + p.prog().metadata.hint());
        
        Matcher m = p.matcher(subject);
        boolean found = m.find();
        System.out.println("found: " + found);
        assertTrue(found, "a\\z should match in b\na");
    }
    
    static String repr(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\\') sb.append("\\\\");
            else sb.append(c);
        }
        return sb.append("'").toString();
    }
}
