package com.idear.devices.card.cardkit.core.exception;

public class CardBlockedException extends CardException {
    public CardBlockedException(String message) {
        super(message);
    }

    public CardBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
