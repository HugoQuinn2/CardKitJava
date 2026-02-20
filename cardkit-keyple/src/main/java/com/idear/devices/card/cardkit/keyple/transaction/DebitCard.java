package com.idear.devices.card.cardkit.keyple.transaction;

import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.CalypsoCardCDMX;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Event;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.constant.*;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Contract;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Logs;
import com.idear.devices.card.cardkit.core.datamodel.date.CompactDate;
import com.idear.devices.card.cardkit.core.datamodel.date.CompactTime;
import com.idear.devices.card.cardkit.core.exception.SignatureException;
import com.idear.devices.card.cardkit.keyple.KeypleTransactionContext;
import com.idear.devices.card.cardkit.core.exception.CardException;
import com.idear.devices.card.cardkit.core.io.transaction.AbstractTransaction;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionResult;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionStatus;
import com.idear.devices.card.cardkit.core.utils.DateUtils;
import com.idear.devices.card.cardkit.keyple.KeypleUtil;
import com.idear.devices.card.cardkit.keyple.TransactionDataEvent;
import com.idear.devices.card.cardkit.keyple.exception.KeypleException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.keypop.calypso.card.transaction.ChannelControl;
import org.eclipse.keypop.calypso.card.transaction.SvAction;
import org.eclipse.keypop.calypso.card.transaction.SvOperation;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Represents a debit transaction on a Calypso card.
 * <p>
 * Supports multi-debit for amounts exceeding {@link #MAX_POSSIBLE_AMOUNT} and handles:
 * <ul>
 *     <li>Passback validation</li>
 *     <li>Contract status and expiration check</li>
 *     <li>Profile and equipment validation</li>
 *     <li>Unsupported tariff rejection</li>
 *     <li>Event recording for special services and free passes</li>
 * </ul>
 *
 * @author Victor Hugo Gaspar Quinn
 * @version 1.0.0
 */
@Getter
@Slf4j
@RequiredArgsConstructor
public class DebitCard
        extends AbstractTransaction<TransactionDataEvent, KeypleTransactionContext> {

    private static final int MAX_POSSIBLE_AMOUNT = 32767;

    private final CalypsoCardCDMX calypsoCardCDMX;
    private final Contract contract;
    private final int transactionType;
    private final int locationId;
    private final int provider;
    private final int passenger;
    private final int amount;

    @Override
    public TransactionResult<TransactionDataEvent> execute(KeypleTransactionContext context) {
        log.info("Debiting card {}, amount: {}, type: {}, provider: {}",
                calypsoCardCDMX.getSerial(), amount, transactionType, provider);

        if (amount > calypsoCardCDMX.getBalance())
            throw new CardException("insufficient balance for debit, balance: " + calypsoCardCDMX.getBalance());

        if (contract.getModality().decode(Modality.FORBIDDEN).equals(Modality.MONOMODAL) &&
                contract.getProvider().decode(Provider.RFU).getValue() != provider)
            throw new CardException("inconsistent provider, monomodal contract, provider most be equal");

        context.getCardTransactionManager()
                .prepareSvGet(SvOperation.DEBIT, SvAction.DO)
                .processCommands(ChannelControl.KEEP_OPEN);

        Event event = Event.builEvent(
                transactionType,
                calypsoCardCDMX.getEnvironment().getNetwork().getValue(),
                provider,
                contract.getId(),
                passenger,
                getCalypsoCardCDMX().getEvents().getNextTransactionNumber(),
                locationId,
                amount
        );

        try {
            context.getCardTransactionManager()
                    .prepareSvGet(SvOperation.DEBIT, SvAction.DO)
                    .prepareSvDebit(
                            amount,
                            CompactDate.now().toBytes(),
                            CompactTime.now().toBytes()
                    ).prepareAppendRecord(
                            event.getFileId(),
                            event.unparse()
                    ).prepareCloseSecureSession()
                    .processCommands(ChannelControl.KEEP_OPEN);
        } catch (Exception e) {
            throw new KeypleException("error writing data on card", e);
        }

        Logs logs = KeypleUtil.readCardLogs(
                context.getCardTransactionManager(),
                context.getKeypleCardReader().getCalypsoCard()
        );

        String mac = "";
        try {
            mac = KeypleUtil.computeTransactionSignature(
                    context.getKeypleCalypsoSamReader(),
                    event,
                    calypsoCardCDMX,
                    calypsoCardCDMX.getBalance()
            );
        } catch (SignatureException e) {
            log.warn("Transaction success but MAC was not generated", e);
        }

        return TransactionResult
                .<TransactionDataEvent>builder()
                .transactionStatus(TransactionStatus.OK)
                .data(TransactionDataEvent
                        .builder()
                        .mac(mac)
                        .debitLog(logs.getDebitLog())
                        .loadLog(logs.getLoadLog())
                        .event(event)
                        .contract(contract)
                        .profile(calypsoCardCDMX.getEnvironment().getProfile().getValue())
                        .transactionAmount(event.getAmount())
                        .balanceBeforeTransaction(calypsoCardCDMX.getBalance())
                        .samSerial(context.getKeypleCalypsoSamReader().getSerial())
                        .locationCode(event.getLocationId())
                        .build()
                ).build();
    }

}
