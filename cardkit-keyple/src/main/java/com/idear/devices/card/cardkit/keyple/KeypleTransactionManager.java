package com.idear.devices.card.cardkit.keyple;

import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.CalypsoCardCDMX;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Contract;
import com.idear.devices.card.cardkit.core.datamodel.date.ReverseDate;
import com.idear.devices.card.cardkit.core.io.transaction.AbstractTransactionManager;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionResult;
import com.idear.devices.card.cardkit.keyple.transaction.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.keypop.calypso.card.WriteAccessLevel;
import org.eclipse.keypop.calypso.card.transaction.ChannelControl;
import org.eclipse.keypop.calypso.card.transaction.SecureRegularModeTransactionManager;

import java.time.LocalDate;

/**
 * Transaction manager implementation based on Keyple for Calypso cards.
 * <p>
 * This manager coordinates secure transactions between the card reader,
 * SAM reader and the card itself, delegating each operation to a specific
 * transaction implementation.
 * </p>
 *
 * <p>
 * Unless explicitly stated otherwise, all transaction methods
 * <b>open and close a secure session internally</b>.
 * </p>
 */
@Getter
@Slf4j
public class KeypleTransactionManager extends AbstractTransactionManager
        <KeypleCardReader, KeypleCalypsoSamReader, KeypleTransactionContext> {

    private final String aid;

    /**
     * Active secure transaction manager for the current card session.
     */
    private volatile SecureRegularModeTransactionManager ctm;

    /**
     * Creates a new Keyple-based transaction manager.
     *
     * @param cardReader the physical card reader
     * @param samReader  the SAM reader used for cryptographic operations
     */
    public KeypleTransactionManager(
            KeypleCardReader cardReader,
            KeypleCalypsoSamReader samReader,
            String aid) {
        super(cardReader, samReader);
        this.aid = aid;
        cardReader.setAid(aid);
    }

    @Override
    public void startCardMonitor() {
        cardReader.setAid(aid);
        super.startCardMonitor();
    }

    /**
     * Invoked when a card is detected.
     * <p>
     * Initializes the secure transaction manager and prepares the
     * cryptographic context required for further operations.
     * </p>
     */
    @Override
    protected void onCardPresent() {
        ctm = KeypleUtil.prepareCardTransactionManger(
                cardReader.getCardReader(),
                cardReader.getCalypsoCard(),
                samReader.getSymmetricCryptoSettingsRT()
        );
    }

    /**
     * Invoked when the card is removed.
     * <p>
     * Ensures that any pending commands are completed and
     * the communication channel is properly closed.
     * </p>
     */
    @Override
    protected void onCardAbsent() {
        ctm.processCommands(ChannelControl.CLOSE_AFTER);
        ctm = null;
    }

    @Override
    protected void onSamPresent() {

    }

    @Override
    protected void onSamAbsent() {
        samReader.getGenericSamTransactionManager()
                .processApdusToByteArrays(org.eclipse.keyple.card.generic.ChannelControl.CLOSE_AFTER);
    }

    /**
     * Creates a new transaction context bound to the current card session.
     *
     * @return a fully initialized {@link KeypleTransactionContext}
     */
    @Override
    protected KeypleTransactionContext createContext() {
        cardReader.setAid(aid);
        return KeypleTransactionContext
                .builder()
                .cardTransactionManager(ctm)
                .keypleCalypsoSamReader(samReader)
                .keypleCardReader(cardReader)
                .build();
    }

    /**
     * Reads all card data using the specified write access level.
     * <p>
     * This method opens a secure session and <b>keeps it open</b> after execution.
     * It is intended to be used as a preliminary step before executing
     * transactional operations.
     * </p>
     *
     * @param writeAccessLevel the write access level required
     * @return a {@link TransactionResult} containing the populated {@link CalypsoCardCDMX}
     *
     * @implNote
     * This is the <b>only operation</b> that does NOT close the secure session.
     */
    public TransactionResult<CalypsoCardCDMX> readCardData(
            WriteAccessLevel writeAccessLevel) {
        return execute(new ReadAllCard(writeAccessLevel));
    }

    /**
     * Executes a debit transaction on the specified contract.
     * <p>
     * The operation deducts the specified amount, records the event
     * and closes the secure session upon completion.
     * </p>
     *
     * @return a {@link TransactionResult} containing debit logs and transaction data
     *
     * @implNote
     * The secure session is closed internally by the transaction.
     */
    public TransactionResult<TransactionDataEvent> debitCard(
            CalypsoCardCDMX calypsoCardCDMX,
            Contract contract,
            int transactionType,
            int locationId,
            int provider,
            int passenger,
            int amount) {
        return execute(new DebitCard(
                calypsoCardCDMX,
                contract,
                transactionType,
                locationId,
                provider,
                passenger,
                amount
        ));
    }

    /**
     * Executes a debit transaction using the first valid contract.
     * <p>
     * The secure session is automatically closed after execution.
     * </p>
     */
    public TransactionResult<TransactionDataEvent> debitCard(
            CalypsoCardCDMX calypsoCardCDMX,
            int transactionType,
            int locationId,
            int provider,
            int passenger,
            int amount) {
        return debitCard(
                calypsoCardCDMX,
                calypsoCardCDMX.getContracts().getFirstContractValid(),
                transactionType,
                locationId,
                provider,
                passenger,
                amount
        );
    }

    /**
     * Reloads balance on the specified contract and closes the secure session.
     */
    public TransactionResult<TransactionDataEvent> reloadCard(
            CalypsoCardCDMX calypsoCardCDMX,
            Contract contract,
            int locationId,
            int provider,
            int passenger,
            int amount) {
        return execute(new ReloadCard(
                calypsoCardCDMX, contract, locationId, provider, passenger, amount
        ));
    }

    /**
     * Reloads balance using the first valid contract.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<TransactionDataEvent> reloadCard(
            CalypsoCardCDMX calypsoCardCDMX,
            int locationId,
            int provider,
            int passenger,
            int amount) {
        return reloadCard(
                calypsoCardCDMX,
                calypsoCardCDMX.getContracts().getFirstContractValid(),
                locationId,
                provider,
                passenger,
                amount
        );
    }

    /**
     * Rehabilitates a previously invalidated card.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<Boolean> rehabilitateCard(
            CalypsoCardCDMX calypsoCardCDMX) {
        return execute(new RehabilitateCard(calypsoCardCDMX));
    }

    /**
     * Invalidates a card contract and records the operation.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<TransactionDataEvent> invalidateCard(
            CalypsoCardCDMX calypsoCardCDMX,
            Contract contract,
            int transactionType,
            int locationId,
            int provider,
            int passenger) {
        return execute(new InvalidateCard(
                calypsoCardCDMX,
                contract,
                transactionType,
                locationId,
                provider,
                passenger
        ));
    }

    /**
     * Invalidates a card using the first valid contract.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<TransactionDataEvent> invalidateCard(
            CalypsoCardCDMX calypsoCardCDMX,
            int transactionType,
            int locationId,
            int provider,
            int passenger) {
        return invalidateCard(
                calypsoCardCDMX,
                calypsoCardCDMX.getContracts().getFirstContractValid(),
                transactionType,
                locationId,
                provider,
                passenger
        );
    }

    /**
     * Cancels the current balance of a contract.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<TransactionDataEvent> balanceCancellation(
            CalypsoCardCDMX calypsoCardCDMX,
            Contract contract,
            int transactionType,
            int locationId,
            int provider,
            int passenger) {
        return execute(new BalanceCancellation(
                calypsoCardCDMX, contract, transactionType, locationId, provider, passenger
        ));
    }

    /**
     * Cancels balance using the first valid contract.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<TransactionDataEvent> balanceCancellation(
            CalypsoCardCDMX calypsoCardCDMX,
            int transactionType,
            int locationId,
            int provider,
            int passenger) {
        return balanceCancellation(
                calypsoCardCDMX,
                calypsoCardCDMX.getContracts().getFirstContractValid(),
                transactionType,
                locationId,
                provider,
                passenger
        );
    }

    /**
     * Renews a contract with new validity parameters.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<TransactionDataEvent> renewContract(
            CalypsoCardCDMX calypsoCardCDMX,
            Contract contract,
            int locationId,
            int provider,
            int passenger,
            ReverseDate startDate,
            int duration) {
        return execute(new RenewedCard(
                calypsoCardCDMX, contract, locationId, provider, passenger, startDate, duration
        ));
    }

    /**
     * Renews the first valid contract.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<TransactionDataEvent> renewContract(
            CalypsoCardCDMX calypsoCardCDMX,
            int locationId,
            int provider,
            int passenger,
            ReverseDate startDate,
            int duration) {
        return execute(new RenewedCard(
                calypsoCardCDMX,
                calypsoCardCDMX.getContracts().getFirstContractValid(),
                locationId,
                provider,
                passenger,
                startDate,
                duration
        ));
    }

    /**
     * Purchases a new contract and writes it to the card.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<TransactionDataEvent> purchaseCard(
            CalypsoCardCDMX calypsoCardCDMX,
            int locationId,
            int contractId,
            int modality,
            int tariff,
            int restrictTime,
            int duration,
            int provider,
            int passenger,
            int amount) {
        return execute(new PurchaseCard(
                calypsoCardCDMX,
                locationId,
                contractId,
                modality,
                tariff,
                restrictTime,
                duration,
                provider,
                passenger,
                amount
        ));
    }

    /**
     * Executes card personalization by writing raw data to a file record.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<Boolean> personalization(
            byte fileId,
            int recordNumber,
            byte[] data) {
        return execute(new Personalization(fileId, recordNumber, data));
    }

    /**
     * Executes card personalization by append raw data to a file record.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<Boolean> appendPersonalization(byte fileId, byte[] data) {
        return execute(new AppendPersonalization(fileId, data));
    }

    /**
     * Executes the pre-personalization phase.
     * <p>Closes the secure session after execution.</p>
     */
    public TransactionResult<Boolean> prePersonalization(
            PrePersonalization.KeyGenerated keyGenerated,
            LocalDate startDate,
            LocalDate endDate) {
        return execute(new PrePersonalization(keyGenerated, startDate, endDate));
    }

    public TransactionResult<Boolean> verifyContractSignature(
            CalypsoCardCDMX calypsoCardCDMX,
            Contract contract) {
        return execute(new VerifyContractSignature(
           calypsoCardCDMX,
           contract
        ));
    }

    public TransactionResult<Boolean> verifyContractSignature(
            CalypsoCardCDMX calypsoCardCDMX) {
        return execute(new VerifyContractSignature(
                calypsoCardCDMX,
                calypsoCardCDMX.getContracts().getFirstContractValid()
        ));
    }

}
