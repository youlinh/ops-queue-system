package com.acme.opsqueue.scheduling;

public class NoEligibleCandidateException extends RuntimeException {

    public NoEligibleCandidateException() {
        super("No eligible assignment candidate");
    }
}
