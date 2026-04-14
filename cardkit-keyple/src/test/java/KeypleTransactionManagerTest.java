import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.Calypso;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.CalypsoCardCDMX;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.constant.*;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.Contract;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.DebitLog;
import com.idear.devices.card.cardkit.core.datamodel.location.LocationCode;
import com.idear.devices.card.cardkit.core.exception.CardException;
import com.idear.devices.card.cardkit.core.exception.CardKitException;
import com.idear.devices.card.cardkit.core.io.transaction.TransactionResult;
import com.idear.devices.card.cardkit.keyple.KeypleCalypsoSamReader;
import com.idear.devices.card.cardkit.keyple.KeypleCardReader;
import com.idear.devices.card.cardkit.keyple.KeypleTransactionManager;
import com.idear.devices.card.cardkit.keyple.TransactionDataEvent;
import com.idear.devices.card.cardkit.core.io.transaction.CardStatus;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.keypop.calypso.card.WriteAccessLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class KeypleTransactionManagerTest {
    public static final String ACS_CARD_READER = ".*ACS ACR1281 1S Dual Reader PICC.*";
    public static final String ACS_SAM_READER = ".*ACS ACR1281 1S Dual Reader SAM.*";
    public static final String lockSecret = "A40B01C39C99CB910FE62A23192A0C5C";

    public static KeypleCardReader kcr;
    public static KeypleCalypsoSamReader kcsr;
    public static KeypleTransactionManager ktm;

    @BeforeAll
    static void initOnce() throws Exception {
        kcsr = new KeypleCalypsoSamReader(ACS_SAM_READER, lockSecret);
        kcr = new KeypleCardReader(ACS_CARD_READER);
        kcr.connect();
        kcsr.connect();
        ktm = new KeypleTransactionManager(kcr, kcsr, Calypso.AID_CDMX);
    }

    @Test
    public void simpleReadCardData() throws Exception {
        System.out.println(ktm.getSamReader().getSamReader().isContactless());
//        ktm.readCardData().print();
    }

    @Test
    public void simpleDebitCard() throws InterruptedException {
        LocationCode locationCode = new LocationCode(0xAAAAAA);
        Provider provider = Provider.CABLEBUS;
        TransactionType transactionType = TransactionType.GENERAL_DEBIT;
        int amount = 100_00;

        ktm.getCardEventList().add(e -> {
            if (!e.equals(CardStatus.CARD_PRESENT))
                return;

            try {
                // Read all card data and validate the operation result
                CalypsoCardCDMX calypsoCardCDMX = ktm.readCardData(WriteAccessLevel.LOAD)
                        .throwException() // throw the exception transaction result and abort all process
                        .getData();

                TransactionResult<Boolean> verifyContract = ktm.verifyContractSignature(
                        calypsoCardCDMX,
                        1_000L
                ).throwException();

                TransactionResult<TransactionDataEvent> transactionResult = ktm.debitCard(
                        calypsoCardCDMX,
                        transactionType.getValue(),
                        locationCode.getValue(),
                        provider.getValue(),
                        0,
                        amount
                ).throwException();

                System.out.println("Debit success!!");
            } catch (CardKitException cardKitException) {
                System.out.println("Debit aborted: " + cardKitException.getMessage());
            } catch (Throwable throwable) {
                System.out.println("Fatal error: " + throwable.getMessage());
            }
        });

        ktm.startCardMonitor();

        Thread.sleep(10000);
    }

    @Test
    public void advancedDebitCard() {

        // Location where the transaction is executed
        LocationCode locationCode = new LocationCode(0xAAAAAA);

        // Transport provider executing the transaction
        Provider provider = Provider.CABLEBUS;

        // Initial debit amount (stored value, cents-based)
        int amount = 10_00;

        // Executor used to persist or process transaction results asynchronously
        ExecutorService executorTransactionResult = Executors.newSingleThreadExecutor();

        // Continuous loop simulating a real reader environment
        while (true) {

            // Block execution until a card is detected on the reader
            kcr.waitForCardPresent(0);

            try {
                // Read all card data and validate the operation result
                CalypsoCardCDMX calypsoCardCDMX = ktm.readCardData(WriteAccessLevel.DEBIT)
                        .throwException()
                        .getData();

                // Verify that the card is enabled
                if (!calypsoCardCDMX.isEnabled())
                    throw new CardException("card disabled");

                // Verify that the card is not on the blacklist
                if (isCardOnBlackList(calypsoCardCDMX.getSerial())) {

                    // Invalidate card and register the transaction
                    TransactionResult<TransactionDataEvent> transactionResult =
                            ktm.invalidateCard(
                                    calypsoCardCDMX,
                                    TransactionType.BLACKLISTED_CARD.getValue(),
                                    locationCode.getValue(),
                                    provider.getValue(),
                                    0
                            );

                    // Save transaction asynchronously
                    executorTransactionResult.submit(() -> System.out.println(transactionResult));

                    throw new CardException("card on black list");
                }

                // Verify that the SAM used in the last debit is trusted
                if (!isLastSamDebitOnWhiteList(calypsoCardCDMX.getLoadLog().getSamId())) {

                    // Cancel balance due to invalid debit SAM
                    TransactionResult<TransactionDataEvent> transactionResult =
                            ktm.balanceCancellation(
                                    calypsoCardCDMX,
                                    TransactionType.BALANCE_CANCELLATION_DEBIT_SAM_NOT_WHITELISTED.getValue(),
                                    locationCode.getValue(),
                                    provider.getValue(),
                                    0
                            );

                    // Save transaction asynchronously
                    executorTransactionResult.submit(() -> System.out.println(transactionResult));

                    throw new CardException("las debit sam out white list");
                }

                // Verify that the SAM used in the last load is trusted
                if (!isLastSamLoadOnWhiteList(calypsoCardCDMX.getLoadLog().getSamId())) {

                    // Cancel balance due to invalid load SAM
                    TransactionResult<TransactionDataEvent> transactionResult =
                            ktm.balanceCancellation(
                                    calypsoCardCDMX,
                                    TransactionType.BALANCE_CANCELLATION_LOAD_SAM_NOT_WHITELISTED.getValue(),
                                    locationCode.getValue(),
                                    provider.getValue(),
                                    0
                            );

                    executorTransactionResult.submit(() -> System.out.println(transactionResult));

                    throw new CardException("las load sam out white list");
                }

                // Retrieve the first valid contract from the card
                Contract contract = calypsoCardCDMX.getContracts().getFirstContractValid();

                // Verify contract expiration
                if (contract.isExpired(0))
                    throw new CardException("card expired");

                // Verify that the user profile is allowed on this equipment
                if (!isProfileValidOnEquipment(
                        calypsoCardCDMX.getEnvironment().getProfile().getValue(),
                        locationCode.getEquipment().getValue()))
                    throw new CardException("profile invalid on this equipment");

                // Decode the profile information
                Profile profile = calypsoCardCDMX.getEnvironment()
                        .getProfile()
                        .decode(Profile.RFU);

                // Special profiles may trigger maintenance or configuration actions
                if (isSpecialProfile(profile)) {
                    // Example: open configuration menu or maintenance mode
                    continue;
                }

                // Passback validation using last debit information
                DebitLog debitLog = calypsoCardCDMX.getDebitLog();

                LocalDateTime lastDebitPassback =
                        debitLog.getLocalDateTime()
                                .plusMinutes(profile.getPassBack());

                // Verify passback using same SAM and time window
                if (kcsr.getSerial().equals(debitLog.getSamId()) &&
                        LocalDateTime.now().isBefore(lastDebitPassback)) {
                    throw new CardException("invalid passback " + lastDebitPassback);
                }

                // Verify that the profile allows access on the current equipment
                if (!profile.isAllowedOn(locationCode.getEquipment()))
                    throw new CardException(
                            "profile not valid on equipment " + locationCode.getEquipment()
                    );

                // Adjust amount depending on the contract tariff
                amount = !contract.getTariff().decode(Tariff.RFU).equals(Tariff.STORED_VALUE)
                        ? 0
                        : amount;

                // Select transaction type based on debit amount
                TransactionType transactionType =
                        amount == 0
                                ? TransactionType.GENERAL_DEBIT
                                : TransactionType.MONOMODAL_FREE_PASS;

                // Execute the debit transaction
                TransactionResult<TransactionDataEvent> transactionResult =
                        ktm.debitCard(
                                        calypsoCardCDMX,
                                        contract,
                                        transactionType.getValue(),
                                        locationCode.getValue(),
                                        provider.getValue(),
                                        0,
                                        amount
                                )
                                .throwException();

                // Persist or publish the transaction result asynchronously
                executorTransactionResult.submit(() -> System.out.println(transactionResult));

                System.out.println("Success debit!!");

            } catch (CardKitException cardKitException) {

                // Controlled business error (validation, rules, card state)
                System.out.println("Debit aborted: " + cardKitException.getMessage());

            } catch (Throwable e) {

                // Unexpected fatal error
                System.out.println("Fatal error: " + e.getMessage());
            }

            // Wait until the card is removed before restarting the loop
            kcr.waitForCarAbsent(0);
        }
    }

    @Test
    public void basicReloadCard() {
        LocationCode locationCode = new LocationCode(0xAAAAAA);
        Provider provider = Provider.CABLEBUS;
        int amount = 100_00;

        while (true) {
            kcr.waitForCardPresent(0);
            CalypsoCardCDMX calypsoCardCDMX = ktm.readCardData(WriteAccessLevel.DEBIT).getData();
            ktm.reloadCard(
                    calypsoCardCDMX,
                    locationCode.getValue(),
                    provider.getValue(),
                    0,
                    amount
            ).print();
            kcr.waitForCarAbsent(0);
        }
    }

    @Test
    public void basicPurchaseCard() {
        LocationCode locationCode = new LocationCode(0xAAAAAA);
        Provider provider = Provider.CABLEBUS;
        int amount = 10_00;

        // Required data for a purchase
        Modality modality = Modality.MONOMODAL;
        Tariff tariff = Tariff.STORED_VALUE;
        RestrictTime restrictTime = RestrictTime.WITHOUT_RESTRICTION;
        int duration = PeriodType.encode(PeriodType.MONTH, 60);

        ktm.getCardEventList().add(e -> {
            if (!e.equals(CardStatus.CARD_PRESENT))
                return;

            try {
                CalypsoCardCDMX calypsoCardCDMX = ktm.readCardData(WriteAccessLevel.LOAD)
                        .throwException()
                        .getData();

                ktm.purchaseCard(
                        calypsoCardCDMX,
                        locationCode.getValue(),
                        1,
                        modality.getValue(),
                        tariff.getValue(),
                        restrictTime.getValue(),
                        duration,
                        provider.getValue(),
                        0,
                        amount
                ).throwException();

                System.out.println("Purchase success!!");
            } catch (CardKitException cardKitException) {
                System.out.println("Purchase aborted: " + cardKitException.getMessage());
            } catch (Throwable ex) {
                System.out.println("Fatal error: " + ex.getMessage());
            }
        });

        ktm.startCardMonitor();

        while (true) {
            try {
                Thread.sleep(200);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void invalidateCard() {
        LocationCode locationCode = new LocationCode(0xAAAAAA);
        Provider provider = Provider.CABLEBUS;

        // Continuous loop simulating a real reader environment
        while (true) {
            kcr.waitForCardPresent(0);
            try {
                CalypsoCardCDMX calypsoCardCDMX = ktm.readCardData(WriteAccessLevel.DEBIT).throwException().getData();
                ktm.invalidateCard(
                        calypsoCardCDMX,
                        TransactionType.BLACKLISTED_CARD.getValue(),
                        locationCode.getValue(),
                        provider.getValue(),
                        0
                ).throwException();
                System.out.println("Invalidate success!!");
            } catch (CardKitException cardKitException) {
                System.out.println("Invalidate aborted: " + cardKitException.getMessage());
            } catch (Throwable throwable) {
                System.out.println("Fatal error: " + throwable.getMessage());
            }
            kcr.waitForCarAbsent(0);
        }
    }

    @Test
    public void rehabilitateCard() {
        // Continuous loop simulating a real reader environment
        while (true) {
            kcr.waitForCardPresent(0);
            try {
                CalypsoCardCDMX calypsoCardCDMX = ktm.readCardData(WriteAccessLevel.DEBIT).throwException().getData();
                ktm.rehabilitateCard(calypsoCardCDMX).throwException();
                System.out.println("Rehabilitate success!!");
            } catch (CardKitException cardKitException) {
                System.out.println("Rehabilitate aborted: " + cardKitException.getMessage());
            } catch (Throwable e) {
                System.out.println("Fatal error: " + e.getMessage());
            }
            kcr.waitForCarAbsent(0);
        }
    }

    @Test
    public void balanceCancellation() {
        LocationCode locationCode = new LocationCode(0xAAAAAA);
        Provider provider = Provider.CABLEBUS;
        TransactionType transactionType = TransactionType.BALANCE_CANCELLATION_DEBIT_SAM_NOT_WHITELISTED;

        // Executor used to persist or process transaction results asynchronously
        ExecutorService executorTransactionResult = Executors.newSingleThreadExecutor();

        // Continuous loop simulating a real reader environment
        while (true) {

            // Block execution until a card is detected on the reader
            kcr.waitForCardPresent(0);

            try {

                // Read all card data and validate the operation result
                CalypsoCardCDMX calypsoCardCDMX = ktm.readCardData(WriteAccessLevel.DEBIT)
                        .throwException() // throw the exception transaction result and abort all process
                        .getData();

                TransactionResult<TransactionDataEvent> transactionResult = ktm.balanceCancellation(
                        calypsoCardCDMX,
                        transactionType.getValue(),
                        locationCode.getValue(),
                        provider.getValue(),
                        0
                ).throwException();

                // Persist or publish the transaction result asynchronously
                executorTransactionResult.submit(() -> System.out.println(transactionResult));

                System.out.println("Balance cancellation success!!");
            } catch (CardKitException cardKitException) {
                System.out.println("Balance cancellation aborted: " + cardKitException.getMessage());
            } catch (Throwable throwable) {
                System.out.println("Fatal error: " + throwable.getMessage());
            }

            kcr.waitForCarAbsent(0);
        }
    }


    public static boolean isProfileValidOnEquipment(int profile, int equipment) {
        return true;
    }

    public static boolean isCardOnBlackList(String serial) {
        return false;
    }

    public static boolean isLastSamDebitOnWhiteList(String serial) {
        return true;
    }

    public static boolean isLastSamLoadOnWhiteList(String serial) {
        return  true;
    }

    public static boolean isSpecialProfile(Profile profile) {
        return profile.equals(Profile.MAINTENANCE);
    }
}
