package com.idear.devices.card.cardkit.keyple;

import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.CalypsoCardCDMX;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.constant.*;
import com.idear.devices.card.cardkit.core.datamodel.calypso.cdmx.file.*;
import com.idear.devices.card.cardkit.core.datamodel.date.CompactDate;
import com.idear.devices.card.cardkit.core.datamodel.date.CompactTime;
import com.idear.devices.card.cardkit.core.datamodel.date.DateTimeReal;
import com.idear.devices.card.cardkit.core.datamodel.date.ReverseDate;
import com.idear.devices.card.cardkit.core.exception.CardException;
import com.idear.devices.card.cardkit.core.exception.SamException;
import com.idear.devices.card.cardkit.core.exception.SignatureException;
import com.idear.devices.card.cardkit.core.io.apdu.ResponseApdu;
import com.idear.devices.card.cardkit.core.io.reader.GenericApduResponse;
import com.idear.devices.card.cardkit.core.utils.BitUtil;
import com.idear.devices.card.cardkit.core.utils.ByteUtils;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.keyple.card.calypso.CalypsoExtensionService;
import org.eclipse.keyple.card.calypso.crypto.legacysam.LegacySamExtensionService;
import org.eclipse.keyple.card.calypso.crypto.legacysam.LegacySamUtil;
import org.eclipse.keyple.card.generic.CardTransactionManager;
import org.eclipse.keyple.card.generic.TransactionException;
import org.eclipse.keyple.core.service.ObservablePlugin;
import org.eclipse.keyple.core.service.SmartCardService;
import org.eclipse.keyple.core.service.SmartCardServiceProvider;
import org.eclipse.keyple.core.util.ByteArrayUtil;
import org.eclipse.keyple.core.util.HexUtil;
import org.eclipse.keyple.plugin.pcsc.PcscPluginFactoryBuilder;
import org.eclipse.keyple.plugin.pcsc.PcscReader;
import org.eclipse.keyple.plugin.pcsc.PcscSupportedContactProtocol;
import org.eclipse.keyple.plugin.pcsc.PcscSupportedContactlessProtocol;
import org.eclipse.keypop.calypso.card.CalypsoCardApiFactory;
import org.eclipse.keypop.calypso.card.PutDataTag;
import org.eclipse.keypop.calypso.card.WriteAccessLevel;
import org.eclipse.keypop.calypso.card.card.*;
import org.eclipse.keypop.calypso.card.transaction.*;
import org.eclipse.keypop.calypso.card.transaction.FreeTransactionManager;
import org.eclipse.keypop.calypso.card.transaction.spi.SymmetricCryptoCardTransactionManagerFactory;
import org.eclipse.keypop.calypso.crypto.legacysam.GetDataTag;
import org.eclipse.keypop.calypso.crypto.legacysam.LegacySamApiFactory;
import org.eclipse.keypop.calypso.crypto.legacysam.sam.LegacySam;
import org.eclipse.keypop.calypso.crypto.legacysam.sam.LegacySamSelectionExtension;
import org.eclipse.keypop.calypso.crypto.legacysam.transaction.*;
import org.eclipse.keypop.reader.CardReader;
import org.eclipse.keypop.reader.ConfigurableCardReader;
import org.eclipse.keypop.reader.ReaderApiFactory;
import org.eclipse.keypop.reader.selection.*;
import org.eclipse.keypop.reader.selection.spi.SmartCard;

import javax.smartcardio.CommandAPDU;
import java.time.LocalDate;
import java.util.*;

/**
 * Utility class providing helper methods to interact with Calypso cards and
 * Legacy SAM modules using the Keyple framework.
 * <p>
 * This class centralizes common operations such as:
 * <ul>
 *   <li>Reader discovery and selection</li>
 *   <li>Calypso card and SAM selection</li>
 *   <li>Secure session management</li>
 *   <li>File read/write operations</li>
 *   <li>Stored value debit/reload</li>
 *   <li>Event logging and transaction signature computation</li>
 * </ul>
 * <p>
 * All methods are static and the class is not intended to be instantiated.
 */
@Slf4j
public abstract class KeypleUtil {

    public static final SmartCardService SMART_CARD_SERVICE = SmartCardServiceProvider.getService();
    public static final ReaderApiFactory READER_API_FACTORY = SMART_CARD_SERVICE.getReaderApiFactory();
    public static final CalypsoExtensionService CALYPSO_EXTENSION_SERVICE = CalypsoExtensionService.getInstance();
    public static final CalypsoCardApiFactory CALYPSO_CARD_API_FACTORY = CALYPSO_EXTENSION_SERVICE.getCalypsoCardApiFactory();
    public static final LegacySamApiFactory LEGACY_SAM_API_FACTORY = LegacySamExtensionService.getInstance().getLegacySamApiFactory();

    public static final ObservablePlugin PLUGIN = (ObservablePlugin) SMART_CARD_SERVICE.registerPlugin(
            PcscPluginFactoryBuilder.builder().setCardMonitoringCycleDuration(5).build());

    /**
     * Returns a list string of pcsc readers connected
     *
     * @return Returns a list string of pcsc readers connected
     */
    public static Set<String> getReaderNames() {
        return PLUGIN.getReaderNames();
    }

    /**
     * Returns the first card reader whose name matches the given regular expression.
     *
     * @param matchName a regular expression used to match the reader name
     * @return the matching {@link CardReader}
     * @throws RuntimeException if no readers are available or none match the pattern
     */
    public static CardReader getCardReaderMatchingName(
            String matchName,
            boolean isContactless) {
        CardReader reader = PLUGIN.getReaders().stream()
                .filter(r -> r.getName().matches(matchName))
                .findFirst()
                .orElse(null);

        if (reader == null)
            throw new IllegalStateException("card reader not founded with name " + matchName);

        PcscReader pcscReader = PLUGIN
                .getReaderExtension(PcscReader.class, reader.getName())
                .setContactless(isContactless)
                .setSharingMode(PcscReader.SharingMode.SHARED);

        if (isContactless) {
            pcscReader.setIsoProtocol(PcscReader.IsoProtocol.T1);

            ((ConfigurableCardReader) reader).activateProtocol(
                    PcscSupportedContactlessProtocol.ISO_14443_4.name(),
                    "ISO_14443_4_CARD");
        } else {
            pcscReader.setIsoProtocol(PcscReader.IsoProtocol.ANY);

            ((ConfigurableCardReader) reader).activateProtocol(
                    PcscSupportedContactProtocol.ISO_7816_3_T0.name(),
                    "ISO_7816_3_T0");
        }
        return reader;
    }

    /**
     * Selects a Calypso card application on the given reader using the provided AID.
     *
     * @param cardReader the reader where the card is inserted
     * @param aid the application identifier (AID)
     * @return the selected {@link CalypsoCard}
     * @throws IllegalStateException if the application selection fails
     */
    public static CalypsoCard selectCard(
            CardReader cardReader,
            String aid) {

        CardSelectionManager cardSelectionManager = READER_API_FACTORY.createCardSelectionManager();
        CalypsoCardSelectionExtension calypsoCardSelection = CALYPSO_CARD_API_FACTORY
                .createCalypsoCardSelectionExtension()
                .acceptInvalidatedCard();

        if (!aid.isEmpty())
            cardSelectionManager.prepareSelection(
                    READER_API_FACTORY.createIsoCardSelector()
                            .filterByDfName(aid),
                    calypsoCardSelection
            );
        else
            cardSelectionManager.prepareSelection(
                    READER_API_FACTORY.createIsoCardSelector(),
                    calypsoCardSelection
            );

        SmartCard smartCard;

        smartCard = cardSelectionManager
                .processCardSelectionScenario(cardReader)
                .getActiveSmartCard();

        if (smartCard == null)
            throw new IllegalStateException("The selection of the application " + aid + " failed.");

        return  (CalypsoCard) smartCard;
    }

    /**
     * Selects a Legacy SAM card from the given reader and unlocks it if required.
     *
     * @param samReader the reader hosting the SAM
     * @param lockSecret the unlock secret (may be {@code null})
     * @return the selected {@link LegacySam}
     * @throws SamException if the SAM cannot be selected or unlocked
     */
    public static LegacySam selectAndUnlockSam(
            CardReader samReader,
            String lockSecret) {

        SMART_CARD_SERVICE.checkCardExtension(CALYPSO_EXTENSION_SERVICE);

        // Create a SAM selection manager.
        CardSelectionManager samSelectionManager = READER_API_FACTORY.createCardSelectionManager();

        // Create a card selector without filter
        CardSelector<BasicCardSelector> samCardSelector = READER_API_FACTORY.createBasicCardSelector();

        // Create a SAM selection
        LegacySamSelectionExtension samSelection = LEGACY_SAM_API_FACTORY
                .createLegacySamSelectionExtension()
                .prepareReadAllCountersStatus();

        // Set lock secret
        if (lockSecret != null)
            samSelection.setUnlockData(lockSecret);

        // Prepare SAM selection
        samSelectionManager.prepareSelection(samCardSelector, samSelection);

        // Get SAM
        LegacySam sam = null;
        try {
            sam = (LegacySam) samSelectionManager
                    .processCardSelectionScenario(samReader)
                    .getActiveSmartCard();
        } catch (Exception ex) {
            throw new SamException("Calypso SAM selection failed in reader " + samReader.getName());
        }

        if (sam == null)
            throw new SamException("No Calypso SAM in reader " + samReader.getName());

        return sam;
    }

    /**
     * Initializes symmetric cryptographic security settings using a Legacy SAM.
     *
     * @param samReader the reader hosting the SAM
     * @param sam the selected Legacy SAM
     * @return a configured {@link SymmetricCryptoSecuritySetting}
     */
    public static SymmetricCryptoSecuritySetting startSymmetricSecuritySettings(
            CardReader samReader,
            LegacySam sam) {
        SymmetricCryptoCardTransactionManagerFactory symmetricCryptoCardTransactionManagerFactory =
                LEGACY_SAM_API_FACTORY
                        .createSymmetricCryptoCardTransactionManagerFactory(
                                samReader, sam);

        return CALYPSO_CARD_API_FACTORY
                .createSymmetricCryptoSecuritySetting(symmetricCryptoCardTransactionManagerFactory)
                .enableSvLoadAndDebitLog()
                .assignDefaultKif(WriteAccessLevel.PERSONALIZATION, (byte) 0x21)
                .assignDefaultKif(WriteAccessLevel.LOAD, (byte) 0x27)
                .assignDefaultKif(WriteAccessLevel.DEBIT, (byte) 0x30);
    }

    /**
     * Computes a transaction MAC using the SAM.
     *
     * @param keypleCalypsoSamReader the SAM reader wrapper
     * @param eventType the transaction type
     * @param transactionTimestamp the transaction timestamp
     * @param transactionAmount the transaction amount
     * @param terminalLocation the terminal location code
     * @param cardType the card product type
     * @param cardSerialHex the card serial number (hexadecimal)
     * @param prevSvBalance the previous stored value balance
     * @return the computed MAC as a hexadecimal string
     */
    public static String computeTransactionSignature(
            KeypleCalypsoSamReader keypleCalypsoSamReader,
            int eventType,
            int transactionTimestamp,
            int transactionAmount,
            int terminalLocation,
            int cardType,
            String cardSerialHex,
            int prevSvBalance) {
        BitUtil bit = new BitUtil(0x20 * 8);
        bit.setNextInteger(eventType, 8);
        bit.setNextInteger(transactionTimestamp, 32);
        bit.setNextInteger(Math.abs(transactionAmount), 32);
        bit.setNextInteger(terminalLocation, 32);
        bit.setNextInteger(cardType, 8);
        bit.setNextHexaString(cardSerialHex, 64);
        bit.setNextInteger(prevSvBalance, 32);
        bit.setNextInteger(getSvProvider(eventType), 8);
        bit.setNextInteger(0, 16);
        bit.setNextInteger(0, 24);

        try {
            ResponseApdu response = digestMacCompute(
                    keypleCalypsoSamReader,
                    (byte) 0xEB,
                    (byte) 0xC0,
                    bit.getData());

            return HexUtil.toHex(response.getData());
        } catch (Exception e) {
            throw new SignatureException("error computing mac transaction signature", e);
        }
    }

    private static int getSvProvider(int et) {
        if (et == 0 || et == 2 || et == 3 || et == 5 || et == 6 ||
                et == 0x0B || et == 0x0C || et == 0x12 ||
                et == 0x14 || et == 0x15)
            return 0xC0;
        else
            return 0x00;
    }

    /**
     * Computes a transaction MAC using the SAM.
     *
     * @param keypleCalypsoSamReader the SAM reader wrapper
     * @param event the event to use
     * @param calypsoCardCDMX the calypso card data to use
     * @param prevSvBalance the previous stored value balance
     * @return the computed MAC as a hexadecimal string
     */
    public static String computeTransactionSignature(
            KeypleCalypsoSamReader keypleCalypsoSamReader,
            Event event,
            CalypsoCardCDMX calypsoCardCDMX,
            int prevSvBalance) {
        if (!event.getTransactionType().decode(TransactionType.RFU).isSigned())
            return "";

        return computeTransactionSignature(
                keypleCalypsoSamReader,
                event.getTransactionType().getValue(),
                DateTimeReal.now().getValue(),
                event.getAmount(),
                event.getLocationId().getValue(),
                calypsoCardCDMX.getCalypsoProduct().getValue(),
                calypsoCardCDMX.getSerial(),
                prevSvBalance
        );
    }

    /**
     * Sends a DIGEST MAC COMPUTE command to the SAM.
     *
     * @param keypleCalypsoSamReade the SAM transaction manager
     * @param kif the key identifier
     * @param kvc the key version
     * @param data the data to authenticate
     * @return a {@link GenericApduResponse} containing the MAC and status word
     */
    public static ResponseApdu digestMacCompute(
            KeypleCalypsoSamReader keypleCalypsoSamReade,
            byte kif,
            byte kvc,
            byte[] data) {

        byte[] _data = new byte[data.length + 2];
        _data[0] = kif;
        _data[1] = kvc;
        System.arraycopy(data, 0, _data, 2, data.length);

        try {
            return keypleCalypsoSamReade.simpleCommand(
                    new CommandAPDU(
                            0x80, 0x8F, 0x00, 0x00,
                            _data
                    )
            ).throwIsNotSuccess();
        } catch (Exception e) {
            throw new SamException("error digesting mac compute", e);
        }
    }

    /**
     * Reads the debit and load logs from the card.
     *
     * @param ctm the transaction manager
     * @param calypsoCard the Calypso card
     * @return a {@link Logs} object containing debit and load logs
     */
    public static Logs readCardLogs(
            SecureRegularModeTransactionManager ctm,
            CalypsoCard calypsoCard) {
//        ctm
//                .prepareOpenSecureSession(WriteAccessLevel.DEBIT)
//                .prepareSvGet(SvOperation.DEBIT, SvAction.DO)
//                .prepareCloseSecureSession()
//                .processCommands(ChannelControl.KEEP_OPEN);

        Logs logs = new Logs();
        logs.setDebitLog(new DebitLog().parse(calypsoCard.getSvDebitLogLastRecord()));
        logs.setLoadLog(new LoadLog().parse(calypsoCard.getSvLoadLogRecord()));

        return logs;
    }

    /**
     * Builds the data structure required to compute a traceable signature for a contract.
     *
     * @param fullSerial the full card serial number
     * @param contract the contract to sign
     * @return a {@link TraceableSignatureComputationData} instance
     */
    public static TraceableSignatureComputationData buildSignatureComputationData(
            byte[] fullSerial,
            Contract contract) {
        return LEGACY_SAM_API_FACTORY
                .createTraceableSignatureComputationData()
                .withSamTraceabilityMode(
                        0xD0,
                        SamTraceabilityMode.FULL_SERIAL_NUMBER)
                .setSignatureSize(3)
                .setData(buildSignatureData(fullSerial, contract), (byte) 0x2C, (byte) 0xC4);
    }

    /**
     * Build the signature contract data with serial card
     *
     * @param fullSerial the serial card
     * @param contract the contract
     * @return the signature data
     */
    public static byte[] buildSignatureData(
            byte[] fullSerial,
            Contract contract) {
        byte[] signatureData = new byte[34];
        System.arraycopy(fullSerial, 0, signatureData, 0, 8);

        byte[] _contract = contract.unparse();
        System.arraycopy(_contract, 0, signatureData, 8, _contract.length - 3);

        signatureData[9] = 0; // ContractStatus
        signatureData[10] = 0; // ContractRfu and ContractValidityStartDate MSb
        signatureData[11] = 0; // ContractValidityStartDate LSb

        return signatureData;
    }

    /**
     * Prepares and renews a contract by computing and applying a SAM signature.
     *
     * @param ctm the transaction manager
     * @param fullSerial the full card serial number
     * @param contract the existing contract
     * @param provider the service provider identifier
     * @param startDate the new contract start date
     * @param duration the contract duration
     * @return the renewed {@link Contract}
     */
    public static Contract setupRenewContract(
            SecureRegularModeTransactionManager ctm,
            byte[] fullSerial,
            Contract contract,
            int provider,
            ReverseDate startDate,
            int duration) {

        Contract wEfContract = new Contract(contract.getId()).parse(contract.unparse());
        wEfContract.getVersion().setValue(Version.VERSION_3_3);
        wEfContract.setRfu(0);
        wEfContract.getStatus().setValue(0);
        wEfContract.setStartDate(ReverseDate.zero());
        wEfContract.setDuration(duration);
//        wEfContract.getNetwork().setValue(NetworkCode.CDMX);
        wEfContract.getProvider().setValue(provider);
//        wEfContract.getModality().setValue(Modality.MONOMODAL);
//        wEfContract.getTariff().setValue(Tariff.STORED_VALUE);
        wEfContract.setJourneyInterChanges(1);
//        wEfContract.setSaleDate(CompactDate.now());
        wEfContract.setAuthKvc((byte) 0xC4 & 0xff);

        // Compute contract signature
        try {
            TraceableSignatureComputationData signatureData = buildSignatureComputationData(
                    fullSerial,
                    wEfContract);
            ctm.getCryptoExtension(CardTransactionLegacySamExtension.class)
                    .prepareComputeSignature(signatureData);
            ctm.processCommands(ChannelControl.KEEP_OPEN);

            // Get signed contract
            int signature = ByteArrayUtil.extractInt(
                    signatureData.getSignature(),
                    0, 3, false);

            byte[] signedContract = Arrays.copyOfRange(
                    signatureData.getSignedData(),
                    fullSerial.length, signatureData.getSignedData().length);

            wEfContract.parse(signedContract);

            // Set signature independent fields
            wEfContract.setRfu(0);
            wEfContract.getStatus().setValue(ContractStatus.CONTRACT_PARTLY_USED);
            wEfContract.setStartDate(startDate);
            wEfContract.setAuthenticator(signature);
            wEfContract.update();

            return wEfContract;
        } catch (Exception e) {
            throw new CardException("Error building contract signature", e);
        }
    }

    /**
     * Creates a secure regular mode transaction manager for a Calypso card.
     *
     * @param cardReader the reader hosting the card
     * @param calypsoCard the selected Calypso card
     * @param symmetricCryptoSecuritySetting the security settings to apply
     * @return a {@link SecureRegularModeTransactionManager}
     */
    public static SecureRegularModeTransactionManager prepareCardTransactionManger(
            CardReader cardReader,
            CalypsoCard calypsoCard,
            SymmetricCryptoSecuritySetting symmetricCryptoSecuritySetting) {
        return CALYPSO_CARD_API_FACTORY
                .createSecureRegularModeTransactionManager(
                        cardReader,
                        calypsoCard,
                        symmetricCryptoSecuritySetting
                );
    }

    public static LegacySam selectLegacySamByProduct(
            CardReader samReader,
            LegacySam.ProductType productType) {
        // Create a SAM selection manager.
        CardSelectionManager samSelectionManager = KeypleUtil.READER_API_FACTORY.createCardSelectionManager();

        // Create a card selector without filer
        IsoCardSelector cardSelector =
                KeypleUtil.READER_API_FACTORY
                        .createIsoCardSelector()
                        .filterByPowerOnData(
                                LegacySamUtil.buildPowerOnDataFilter(productType, null));

        // Create a SAM selection using the Calypso card extension.
        samSelectionManager.prepareSelection(
                cardSelector, LEGACY_SAM_API_FACTORY.createLegacySamSelectionExtension());

        // SAM communication: run the selection scenario.
        CardSelectionResult samSelectionResult =
                samSelectionManager.processCardSelectionScenario(samReader);

        // Check the selection result.
        if (samSelectionResult.getActiveSmartCard() == null) {
            throw new SamException("The selection of the SAM failed, product " + productType + " required on sam");
        }

        return (LegacySam) samSelectionResult.getActiveSmartCard();
    }

    public static void cardSamKeyPair(
            CardReader cardReader,
            CardReader samReader,
            LegacySam legacySam,
            CalypsoCard calypsoCard,
            LocalDate startDate,
            LocalDate endDate) {
        FreeTransactionManager freeTransactionManager = CALYPSO_CARD_API_FACTORY
                .createFreeTransactionManager(cardReader, calypsoCard);
        try {
            freeTransactionManager
                    .prepareGenerateAsymmetricKeyPair()
                    .prepareGetData(org.eclipse.keypop.calypso.card.GetDataTag.CARD_PUBLIC_KEY)
                    .processCommands(ChannelControl.KEEP_OPEN);
        } catch (Exception e) {
            throw new CardException("Error making ECC key pair: " + e.getMessage());
        }

        LegacyCardCertificateComputationData cardCertificateComputationData =
                LEGACY_SAM_API_FACTORY
                        .createLegacyCardCertificateComputationData()
                        .setCardAid(calypsoCard.getDfName())
                        .setCardSerialNumber(calypsoCard.getApplicationSerialNumber())
                        .setStartDate(startDate)
                        .setEndDate(endDate)
                        .setCardStartupInfo(calypsoCard.getStartupInfoRawData());

        try {
            LEGACY_SAM_API_FACTORY
                    .createFreeTransactionManager(samReader, legacySam)
                    .prepareGetData(GetDataTag.CA_CERTIFICATE)
                    .prepareComputeCardCertificate(cardCertificateComputationData)
                    .processCommands();
        } catch (Exception e) {
            throw new SamException("Error generating PKI sam certification: " + e.getMessage());
        }

        try {
            CALYPSO_CARD_API_FACTORY
                    .createFreeTransactionManager(cardReader, calypsoCard)
                    .preparePutData(PutDataTag.CA_CERTIFICATE, legacySam.getCaCertificate())
                    .preparePutData(PutDataTag.CARD_CERTIFICATE, cardCertificateComputationData.getCertificate())
                    .processCommands(ChannelControl.KEEP_OPEN);
        } catch (Exception e) {
            throw new CardException("Error putting sam certification data on card: " + e.getMessage());
        }
    }

    public static void legacySamKeyPair(
            CardReader cardReader,
            CardReader samReader,
            LegacySam legacySam,
            CalypsoCard calypsoCard,
            LocalDate startDate,
            LocalDate endDate) {
        KeyPairContainer keyPairContainer = LEGACY_SAM_API_FACTORY.createKeyPairContainer();
        LegacyCardCertificateComputationData cardCertificateComputationData =
                LEGACY_SAM_API_FACTORY
                        .createLegacyCardCertificateComputationData()
                        .setCardAid(calypsoCard.getDfName())
                        .setCardSerialNumber(calypsoCard.getApplicationSerialNumber())
                        .setStartDate(startDate)
                        .setEndDate(endDate)
                        .setCardStartupInfo(calypsoCard.getStartupInfoRawData());

        try {
            LEGACY_SAM_API_FACTORY
                    .createFreeTransactionManager(samReader, legacySam)
                    .prepareGetData(GetDataTag.CA_CERTIFICATE)
                    .prepareGenerateCardAsymmetricKeyPair(keyPairContainer)
                    .prepareComputeCardCertificate(cardCertificateComputationData)
                    .processCommands();
        } catch (Exception e) {
            throw new SamException("Error generating PKI sam certification: " + e.getMessage());
        }

        try {
            CALYPSO_CARD_API_FACTORY
                    .createFreeTransactionManager(cardReader, calypsoCard)
                    .preparePutData(PutDataTag.CA_CERTIFICATE, legacySam.getCaCertificate())
                    .preparePutData(PutDataTag.CARD_KEY_PAIR, keyPairContainer.getKeyPair())
                    .preparePutData(PutDataTag.CA_CERTIFICATE, cardCertificateComputationData.getCertificate())
                    .processCommands(ChannelControl.KEEP_OPEN);
        }catch (Exception e) {
            throw new CardException("Error putting sam certification data on card: " + e.getMessage());
        }
    }

    /**
     * Traceability information starts at bit-offset <code>D0h</code>:
     * <ol>
     *     <li>SAM serial number: <code>AA BB CC DD</code>, followed by
     *     <li>Key counter: <code>X1 X2 X3</code></li>
     * </ol>
     *
     * The signature <b>verification</b> key must be diversified with the value:
     * <code>00 00 00 00 00 00 XX YY</code> where,
     * <ol>
     *     <li><code>XX</code> is the ContractNetworkId</li>
     *     <li><code>YY</code> is the ContractProvider</li>
     * </ol>
     *
     * The signature <b>verification</b> key gets its KVC from <code>ContractAuthKvc</code>.
     *
     * @param fullSerial Calypso serial number
     * @param efContract Contract data
     * @return {@link TraceableSignatureVerificationData}
     */
    public static TraceableSignatureVerificationData buildTraceableSignatureVerificationData(
            byte[] fullSerial, Contract efContract) {
        // Diversifier
        byte[] diversifier = new byte[8];
        diversifier[6] = (byte) efContract.getNetwork().getValue();
        diversifier[7] = (byte) efContract.getProvider().getValue();

        // Signature KVC
        byte contractAuthKvc = (byte) efContract.getAuthKvc();

        // traceability data offset
        int offset = 0xD0;

        // Signature data
        byte[] signatureData = buildSignatureData(fullSerial, efContract);

        // Signature
        int signatureSize = 3;
        byte[] signature = ByteArrayUtil.extractBytes(
                efContract.getAuthenticator(),
                signatureSize);

        return LegacySamExtensionService.getInstance()
                .getLegacySamApiFactory()
                .createTraceableSignatureVerificationData()
                .withSamTraceabilityMode(
                        offset,
                        SamTraceabilityMode.FULL_SERIAL_NUMBER,
                        null)
                .setKeyDiversifier(diversifier)
                .setData(
                        signatureData,
                        signature,
                        (byte) 0x2B,
                        contractAuthKvc
                );
    }

}
