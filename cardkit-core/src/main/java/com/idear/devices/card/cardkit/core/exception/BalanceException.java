package com.idear.devices.card.cardkit.core.exception;

public class BalanceException extends CardException {
    public BalanceException(String message) {
        super(message);
    }

    public BalanceException(String message, Throwable cause) {
      super(message, cause);
    }
}
