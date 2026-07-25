package com.acme.opsqueue.roster;

public record RosterImportError(int rowNumber, String message) {
}
