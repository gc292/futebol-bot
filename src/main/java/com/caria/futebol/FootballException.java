package com.caria.futebol;

/** A validation or conflict error safe to present in a Discord reply. */
final class FootballException extends RuntimeException {
    FootballException(String message) { super(message); }
}
