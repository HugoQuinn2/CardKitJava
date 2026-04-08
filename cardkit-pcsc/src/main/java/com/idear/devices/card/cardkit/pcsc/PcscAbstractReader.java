package com.idear.devices.card.cardkit.pcsc;

import com.idear.devices.card.cardkit.core.io.apdu.ResponseApdu;
import com.idear.devices.card.cardkit.core.io.reader.AbstractReader;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.eclipse.keyple.core.util.HexUtil;

import javax.smartcardio.*;

@EqualsAndHashCode(callSuper = true)
@Data
public class PcscAbstractReader extends AbstractReader {

    public static final String ACS_CARD_READER = ".*ACS ACR1281 1S Dual Reader PICC.*";
    public static final String ACS_SAM_READER = ".*ACS ACR1281 1S Dual Reader SAM.*";

    private final String name;

    @ToString.Exclude
    private CardTerminal cardTerminal;

    @ToString.Exclude
    private Card card;
    private CardChannel cardChannel;

    @Override
    public void connect() throws Exception {
        cardTerminal = PcscUtil.getCardTerminalMatchingName(name);
    }

    @Override
    public void disconnect() {
        if (card != null)
            try {
                card.disconnect(false);
            } catch (CardException e) {
                throw new RuntimeException(e);
            }
    }

    @Override
    public boolean isCardOnReader() {
        if (cardTerminal == null)
            return false;

        try {
            return cardTerminal.isCardPresent();
        } catch (CardException e) {
            return false;
        }
    }

    @Override
    public void connectToCard() {
        try {
            card = cardTerminal.connect("*");
            cardChannel = card.getBasicChannel();
        } catch (CardException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void disconnectFromCard() {
        if (card != null)
            try {
                card.disconnect(false);
                card = null;
                cardChannel = null;
            } catch (CardException e) {
                throw new RuntimeException(e);
            }
    }

    @Override
    public void waitForCardPresent(long l) {
        if (cardTerminal == null)
            return;

        if (isCardOnReader())
            return;

        try {
            cardTerminal.waitForCardPresent(l);
            connectToCard();
        } catch (CardException e) {
            return;
        }
    }

    @Override
    public void waitForCarAbsent(long l) {
        if (cardTerminal == null)
            return;

        if (!isCardOnReader())
            return;

        try {
            cardTerminal.waitForCardAbsent(l);
            disconnectFromCard();
        } catch (CardException e) {
            return;
        }
    }


    @Override
    public ResponseApdu simpleCommand(CommandAPDU command) {
        if (cardChannel != null)
            try {
                ResponseAPDU responseAPDU = cardChannel.transmit(command);
                if (responseAPDU.getSW() != 0x9000)
                    throw new RuntimeException(
                            String.format("bad status response, waited: %s, actual: %s, command: %s",
                                    9000, HexUtil.toHex(responseAPDU.getSW()), HexUtil.toHex(command.getBytes()))
                    );
                return new ResponseApdu(responseAPDU.getBytes());
            } catch (CardException e) {
                throw new RuntimeException(e);
            }

        return null;
    }

}
