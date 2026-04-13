package com.idear.devices.card.cardkit.keyple.transaction;

import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.CalypsoCardCDMX;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Contract;
import com.idear.devices.card.cardkit.core.io.transaction.AbstractTransaction;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionResult;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionStatus;
import com.idear.devices.card.cardkit.keyple.KeypleTransactionContext;
import com.idear.devices.card.cardkit.keyple.KeypleUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.keyple.core.util.HexUtil;
import org.eclipse.keypop.calypso.card.transaction.ChannelControl;
import org.eclipse.keypop.calypso.crypto.legacysam.transaction.CardTransactionLegacySamExtension;

@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@RequiredArgsConstructor
public class VerifyContractSignature extends AbstractTransaction<Boolean, KeypleTransactionContext> {

    private final CalypsoCardCDMX calypsoCardCDMX;
    private final Contract contract;
    private final Long loopTryVerify;

    public static final Long MAX_TRY_VERIFY = 11_000L;

    @Override
    public TransactionResult<Boolean> execute(KeypleTransactionContext context) {

        long startTime = System.currentTimeMillis();
        long timeoutMs = (loopTryVerify != null) ? loopTryVerify : MAX_TRY_VERIFY;

        while (true) {
            try {
                context.getCardTransactionManager()
                        .getCryptoExtension(CardTransactionLegacySamExtension.class)
                        .prepareVerifySignature(
                                KeypleUtil.buildTraceableSignatureVerificationData(
                                        HexUtil.toByteArray(calypsoCardCDMX.getSerial()),
                                        contract
                                )
                        );

                context.getCardTransactionManager()
                        .processCommands(ChannelControl.KEEP_OPEN);

                log.info("Contract signature verified successfully for serial: {}",
                        calypsoCardCDMX.getSerial());

                return TransactionResult
                        .<Boolean>builder()
                        .transactionStatus(TransactionStatus.OK)
                        .data(true)
                        .build();

            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : "";

                if (message.contains("6982")) {
                    long elapsed = System.currentTimeMillis() - startTime;

                    if (elapsed < timeoutMs) {
                        log.trace("SAM busy (6982), retrying... elapsed={}ms", elapsed);

                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }

                        continue;
                    }

                    log.error("SAM busy timeout exceeded after {}ms", elapsed);
                    break;
                }

                if (message.contains("6988")) {
                    log.warn("Incorrect contract signature (6988) for serial: {}",
                            calypsoCardCDMX.getSerial());
                    break;
                }

                log.error("Contract signature verification failed for serial: {}",
                        calypsoCardCDMX.getSerial(), e);
                break;
            }
        }

        return TransactionResult
                .<Boolean>builder()
                .transactionStatus(TransactionStatus.ERROR)
                .data(false)
                .build();
    }

}
