package com.idear.devices.card.cardkit.keyple.exception;

import com.idear.devices.card.cardkit.core.exception.CardKitException;

public class KeypleException extends CardKitException {

    public KeypleException(String message) {
        super(message);
    }

  public KeypleException(String message, Throwable cause) {
    super(message, cause);
  }

}
