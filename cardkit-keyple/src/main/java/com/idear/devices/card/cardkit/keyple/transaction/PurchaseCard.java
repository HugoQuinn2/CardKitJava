package com.idear.devices.card.cardkit.keyple.transaction;

import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.Calypso;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.CalypsoCardCDMX;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Event;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.constant.*;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Contract;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Logs;
import com.idear.devices.card.cardkit.core.datamodel.date.CompactDate;
import com.idear.devices.card.cardkit.core.datamodel.date.ReverseDate;
import com.idear.devices.card.cardkit.core.exception.SignatureException;
import com.idear.devices.card.cardkit.keyple.KeypleTransactionContext;
import com.idear.devices.card.cardkit.core.io.transaction.AbstractTransaction;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionResult;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionStatus;
import com.idear.devices.card.cardkit.keyple.KeypleUtil;
import com.idear.devices.card.cardkit.keyple.TransactionDataEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.keyple.core.util.HexUtil;
import org.eclipse.keypop.calypso.card.transaction.ChannelControl;

import java.time.LocalDate;

@Slf4j
@RequiredArgsConstructor
public class PurchaseCard extends AbstractTransaction<TransactionDataEvent, KeypleTransactionContext> {

    private final CalypsoCardCDMX calypsoCardCDMX;
    private final int locationId;
    private final int contractId;
    private final int modality;
    private final int tariff;
    private final int restrictTime;
    private final int duration;
    private final int provider;
    private final int passenger;
    private final int amount;

    public TransactionResult<TransactionDataEvent> execute(KeypleTransactionContext context) {
        log.info("Purchasing card {}, modality: {}, tariff: {}, expiration: {}, restrict: {}",
                calypsoCardCDMX.getSerial(), modality, tariff, PeriodType.getExpirationDate(LocalDate.now(), duration), restrictTime);

        Contract contract = Contract.buildContract(
                contractId,
                context.getKeypleCalypsoSamReader().getSamNetworkCode(),
                context.getKeypleCalypsoSamReader().getSamProviderCode().getValue(),
                modality,
                tariff,
                restrictTime,
                context.getKeypleCalypsoSamReader().getSerial()
        );

        contract.setSaleDate(CompactDate.now());
        contract.setSaleSam(context.getKeypleCalypsoSamReader().getSerial());

        Contract _contract = KeypleUtil.setupRenewContract(
                context.getCardTransactionManager(),
                HexUtil.toByteArray(calypsoCardCDMX.getSerial()),
                contract,
                provider,
                ReverseDate.now(),
                duration
        );

        Event event = Event.builEvent(
                TransactionType.CARD_PURCHASE.getValue(),
                calypsoCardCDMX.getEnvironment().getNetwork().getValue(),
                provider,
                _contract.getId(),
                passenger,
                calypsoCardCDMX.getEvents().getNextTransactionNumber(),
                locationId,
                amount
        );

        context.getCardTransactionManager()
                .prepareUpdateRecord(
                        _contract.getFileId(),
                        _contract.getId(),
                        _contract.unparse()
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
                        .contract(_contract)
                        .profile(calypsoCardCDMX.getEnvironment().getProfile().getValue())
                        .transactionAmount(event.getAmount())
                        .balanceBeforeTransaction(calypsoCardCDMX.getBalance())
                        .locationCode(event.getLocationId())
                        .build()
                ).build();
    }

}
