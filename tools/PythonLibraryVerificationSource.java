package org.pocketide.python;

/** Host regression harness: emit the exact source used by Android verification. */
public final class PythonLibraryVerificationSource {
    public static void main(String[] args) throws Exception {
        System.out.write(PythonLibraryVerification.script(args[0], args[1])
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
