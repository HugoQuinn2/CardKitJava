package com.idear.devices.card.cardkit.keyple.transaction;

import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.CalypsoCardCDMX;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.constant.TransactionType;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Event;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Logs;
import com.idear.devices.card.cardkit.core.datamodel.date.CompactDate;
import com.idear.devices.card.cardkit.core.datamodel.date.CompactTime;
import com.idear.devices.card.cardkit.core.exception.SignatureException;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionStatus;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Contract;
import com.idear.devices.card.cardkit.core.utils.ByteUtils;
import com.idear.devices.card.cardkit.keyple.KeypleTransactionContext;
import com.idear.devices.card.cardkit.core.exception.CardException;
import com.idear.devices.card.cardkit.core.io.transaction.AbstractTransaction;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionResult;
import com.idear.devices.card.cardkit.keyple.KeypleUtil;
import com.idear.devices.card.cardkit.keyple.TransactionDataEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.keypop.calypso.card.WriteAccessLevel;
import org.eclipse.keypop.calypso.card.transaction.ChannelControl;
import org.eclipse.keypop.calypso.card.transaction.SvAction;
import org.eclipse.keypop.calypso.card.transaction.SvOperation;

@RequiredArgsConstructor
@Slf4j
public class InvalidateCard extends AbstractTransaction<TransactionDataEvent, KeypleTransactionContext> {

    private final CalypsoCardCDMX calypsoCardCDMX;
    private final Contract contract;
    private final int transactionType;
    private final int locationId;
    private final int provider;
    private final int passenger;

    @Override
    public TransactionResult<TransactionDataEvent> execute(KeypleTransactionContext context) {
        if (!calypsoCardCDMX.isEnabled())
            throw new CardException("card already invalidated");

        Event event = Event.builEvent(
                transactionType,
                calypsoCardCDMX.getEnvironment().getNetwork().getValue(),
                provider,
                contract.getId(),
                passenger,
                calypsoCardCDMX.getEvents().getNextTransactionNumber(),
                locationId,
                0
        );

        context.getCardTransactionManager()
                .prepareOpenSecureSession(WriteAccessLevel.DEBIT)
                .prepareInvalidate()
                .prepareAppendRecord(
                        event.getFileId(),
                        event.unparse()
                ).prepareCloseSecureSession()
                .processCommands(ChannelControl.KEEP_OPEN);

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
                        .locationCode(event.getLocationId())
                        .build()
                ).build();
    }

}
