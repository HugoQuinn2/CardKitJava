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

    @Override
    public TransactionResult<Boolean> execute(KeypleTransactionContext context) {

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

        return TransactionResult
                .<Boolean>builder()
                .transactionStatus(TransactionStatus.OK)
                .data(true)
                .build();

    }

}
