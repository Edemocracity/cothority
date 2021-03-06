package ch.epfl.dedis.lib.omniledger.contracts;

import ch.epfl.dedis.lib.exception.CothorityCommunicationException;
import ch.epfl.dedis.lib.exception.CothorityCryptoException;
import ch.epfl.dedis.lib.exception.CothorityException;
import ch.epfl.dedis.lib.exception.CothorityNotFoundException;
import ch.epfl.dedis.lib.omniledger.*;
import ch.epfl.dedis.lib.omniledger.darc.*;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.util.Arrays;
import java.util.List;

/**
 * ValueInstance represents a simple value store on omniledger.
 */
public class ValueInstance {
    private Instance instance;
    private OmniledgerRPC ol;
    private byte[] value;

    private final static Logger logger = LoggerFactory.getLogger(ValueInstance.class);

    /**
     * Instantiates a new ValueInstance given a working omniledger instance and
     * an instanceId. This instantiator will contact omniledger and try to get
     * the current valueInstance. If the instance is not found, or is not of
     * contractId "Value", an exception will be thrown.
     *
     * @param ol is a link to an omniledger instance that is running
     * @param id of the value-instance to connect to
     * @throws CothorityException
     */
    public ValueInstance(OmniledgerRPC ol, InstanceId id) throws CothorityException {
        this.ol = ol;
        Proof p = ol.getProof(id);
        instance = new Instance(p);
        if (!instance.getContractId().equals("value")) {
            logger.error("wrong instance: {}", instance.getContractId());
            throw new CothorityNotFoundException("this is not a value instance");
        }
        value = instance.getData();
    }

    public ValueInstance(OmniledgerRPC ol, Proof p) throws CothorityException {
        this(ol, new InstanceId(p.getKey()));
    }

    public void update() throws CothorityException {
        instance = new Instance(ol.getProof(instance.getId()));
        value = instance.getData();
    }

    /**
     * Creates an instruction to evolve the value in omniledger. The signer must have its identity in the current
     * darc as "invoke:update" rule.
     * <p>
     * TODO: allow for evolution if the expression has more than one identity.
     *
     * @param newValue the value to replace the old value.
     * @param owner    must have its identity in the "invoke:update" rule
     * @param pos      position of the instruction in the ClientTransaction
     * @param len      total number of instructions in the ClientTransaction
     * @return Instruction to be sent to omniledger
     * @throws CothorityCryptoException
     */
    public Instruction evolveValueInstruction(byte[] newValue, Signer owner, int pos, int len) throws CothorityCryptoException {
        Invoke inv = new Invoke("update", "value", newValue);
        Instruction inst = new Instruction(instance.getId(), SubId.random().getId(), pos, len, inv);
        try {
            Request r = new Request(instance.getId().getDarcId(), "invoke:update", inst.hash(),
                    Arrays.asList(owner.getIdentity()), null);
            logger.info("Signing: {}", DatatypeConverter.printHexBinary(r.hash()));
            Signature sign = new Signature(owner.sign(r.hash()), owner.getIdentity());
            inst.setSignatures(Arrays.asList(sign));
        } catch (Signer.SignRequestRejectedException e) {
            throw new CothorityCryptoException(e.getMessage());
        }
        return inst;
    }

    public TransactionId evolveValue(byte[] newValue, Signer owner) throws CothorityException {
        Instruction inst = evolveValueInstruction(newValue, owner, 0, 1);
        ClientTransaction ct = new ClientTransaction(Arrays.asList(inst));
        ol.sendTransaction(ct);
        return new TransactionId(instance.getId().getDarcId(), SubId.zero());
    }

    /**
     * Asks omniledger to update the value and waits until the new value has
     * been stored in the global state.
     * TODO: check if there has been an error in the transaction!
     *
     * @param newValue the value to replace the old value.
     * @param owner   is the owner that can sign to evolve the darc
     * @throws CothorityException
     */
    public void evolveValueAndWait(byte[] newValue, Signer owner) throws CothorityException {
        evolveValue(newValue, owner);
        for (int i = 0; i < 10; i++) {
            Proof p = ol.getProof(instance.getId());
            Instance inst = new Instance(p);
            logger.info("Values are: {} - {}", DatatypeConverter.printHexBinary(inst.getData()),
                    DatatypeConverter.printHexBinary(newValue));
            if (Arrays.equals(inst.getData(), newValue)){
                value = newValue;
                return;
            }
            try{
                Thread.sleep(ol.getConfig().getBlockInterval().toMillis());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        throw new CothorityCommunicationException("couldn't find new value");
    }

    /**
     * @return the id of the instance
     * @throws CothorityCryptoException
     */
    public InstanceId getId() throws CothorityCryptoException {
        return instance.getId();
    }

    /**
     * @return a copy of the value stored in this instance.
     */
    public byte[] getValue() throws CothorityCryptoException {
        byte[] v = new byte[value.length];
        System.arraycopy(value, 0, v, 0, value.length);
        return v;
    }

    /**
     * @return the instance used.
     */
    public Instance getInstance() {
        return instance;
    }
}
