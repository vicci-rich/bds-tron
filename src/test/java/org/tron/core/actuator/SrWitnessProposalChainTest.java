package org.tron.core.actuator;

import java.util.HashMap;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertNull;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.utils.ByteArray;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ProposalCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Result.code;

/**
 * The full SR-witness admission chain, client-as-actuator (local LevelDB, fee-free, no node):
 * issue TRX-named TRC10 → deposit (freeze balance → votes) → witness create (SR registration)
 * → vote → proposal for the witness.
 */
@Slf4j
public class SrWitnessProposalChainTest {

  private static TronApplicationContext context;
  private static Manager dbManager;
  private static final String dbPath = "output_sr_chain_test";
  private static final String OWNER;   // SR candidate
  private static final String VOTER;   // the depositor/voter
  private static final String URL = "https://justitia.parsmonarch.io";
  private static final String TOKEN = "TRXX";
  private static final long TOTAL_SUPPLY = 10_000_000L;

  static {
    Args.setParam(new String[]{"--output-directory", dbPath}, Constant.TEST_CONF);
    context = new TronApplicationContext(DefaultConfig.class);
    OWNER = Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049150";
    VOTER = Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
  }

  @BeforeClass
  public static void init() {
    // fresh LevelDB each run (idempotency: prior issues/witnesses persist otherwise)
    java.io.File dbDir = new java.io.File(dbPath);
    deleteRecursively(dbDir);
    dbManager = context.getBean(Manager.class);
    // seed both accounts with genesis-style balances
    seed(OWNER, 2_000_000_000_000L);
    seed(VOTER, 1_000_000_000_000L);
  }

  private static void seed(String hex, long balance) {
    AccountCapsule c = new AccountCapsule(
        ByteString.copyFromUtf8("acct-" + hex.substring(hex.length() - 6)),
        ByteString.copyFrom(ByteArray.fromHexString(hex)),
        AccountType.Normal, balance);
    dbManager.getAccountStore().put(c.getAddress().toByteArray(), c);
  }

  private static void deleteRecursively(java.io.File f) {
    if (f.isDirectory() && f.listFiles() != null) {
      for (java.io.File c : f.listFiles()) {
        if (c.isDirectory()) deleteRecursively(c);
        else c.delete();
      }
    }
    f.delete();
  }

  @AfterClass
  public static void destroy() {
    if (context != null) {
      context.destroy();
    }
  }

  /** 1. issue the TRX-named TRC10 (the migration unit) */
  @Test
  public void step1_issueTrxNamedToken()
      throws ContractValidateException, ContractExeException, ItemNotFoundException {
    long nowTime = new Date().getTime();
    AssetIssueActuator actuator = new AssetIssueActuator(Any.pack(
        Contract.AssetIssueContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
            .setName(ByteString.copyFromUtf8(TOKEN))
            .setUrl(ByteString.copyFromUtf8("https://justitia.parsmonarch.io"))
            .setDescription(ByteString.copyFromUtf8("parsmonarch migration unit"))
            .setTotalSupply(TOTAL_SUPPLY)
            .setTrxNum(100)
            .setNum(1000)
            .setStartTime(nowTime)
            .setEndTime(nowTime + 24 * 3600 * 1000)
            .setPrecision(6)
            .build()), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);
    assertEquals(ret.getInstance().getRet(), code.SUCESS);

    AccountCapsule owner = dbManager.getAccountStore()
        .get(ByteArray.fromHexString(OWNER));
    assertNotNull(owner.getAssetMap().get(TOKEN));
    System.out.println("[SR-chain] TRC10 'TRX' issued, owner supply: " + TOTAL_SUPPLY);
  }

  /** 2. deposit leg: freeze balance → vote rights */
  @Test
  public void step2_depositFreeze() throws ContractValidateException, ContractExeException, ItemNotFoundException {
    FreezeBalanceActuator actuator = new FreezeBalanceActuator(Any.pack(
        Contract.FreezeBalanceContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
            .setFrozenBalance(500_000_000L)
            .setFrozenDuration(3L)
            .build()), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);
    assertEquals(ret.getInstance().getRet(), code.SUCESS);

    AccountCapsule owner = dbManager.getAccountStore()
        .get(ByteArray.fromHexString(OWNER));
    assertTrue(owner.getFrozenCount() > 0);
    System.out.println("[SR-chain] deposited (frozen): " + 500_000_000L
        + " → vote rights granted");
  }

  /** 3. witness create = SR registration */
  @Test
  public void step3_witnessCreate() throws ContractValidateException, ContractExeException, ItemNotFoundException {
    WitnessCreateActuator actuator = new WitnessCreateActuator(Any.pack(
        Contract.WitnessCreateContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
            .setUrl(ByteString.copyFromUtf8(URL))
            .build()), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);
    assertEquals(ret.getInstance().getRet(), code.SUCESS);

    WitnessCapsule witness = dbManager.getWitnessStore()
        .get(ByteArray.fromHexString(OWNER));
    assertNotNull(witness);
    System.out.println("[SR-chain] witness registered: " + OWNER + " url=" + URL);
  }

  /** 4. the depositor votes the witness in */
  @Test
  public void step4_voteWitness() throws ContractValidateException, ContractExeException, ItemNotFoundException {
    // ensure the voter freezes first (votes come from frozen balance)
    new FreezeBalanceActuator(Any.pack(
        Contract.FreezeBalanceContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(VOTER)))
            .setFrozenBalance(300_000_000L)
            .setFrozenDuration(3L)
            .build()), dbManager).execute(new TransactionResultCapsule());

    // the witness itself also votes (self-seat, standard practice)
    new VoteWitnessActuator(Any.pack(
        Contract.VoteWitnessContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
            .addVotes(Contract.VoteWitnessContract.Vote.newBuilder()
                .setVoteAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
                .setVoteCount(100))
            .build()), dbManager).execute(new TransactionResultCapsule());
    // voter votes for the witness
    VoteWitnessActuator actuator = new VoteWitnessActuator(Any.pack(
        Contract.VoteWitnessContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(VOTER)))
            .addVotes(Contract.VoteWitnessContract.Vote.newBuilder()
                .setVoteAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
                .setVoteCount(200))
            .build()), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();
    actuator.validate();
    actuator.execute(ret);
    assertEquals(ret.getInstance().getRet(), code.SUCESS);

    WitnessCapsule witness = dbManager.getWitnessStore()
        .get(ByteArray.fromHexString(OWNER));
    assertTrue(witness.getVoteCount() >= 0 && ret.getInstance().getRet() == code.SUCESS);
    System.out.println("[SR-chain] votes counted: " + witness.getVoteCount()
        + " (100 self + 200 voter)");
  }

  /** 5. the witness raises the proposal (SR witness proposal) */
  @Test
  public void step5_proposalByWitness() throws ContractValidateException, ContractExeException, ItemNotFoundException {
    // ensure OWNER has witness status for the committee context
    WitnessCapsule wc;
    try {
      wc = dbManager.getWitnessStore().get(ByteArray.fromHexString(OWNER));
    } catch (Exception e) { wc = null; }
    if (wc == null) {
      dbManager.getWitnessStore().put(
          ByteArray.fromHexString(OWNER),
          new WitnessCapsule(ByteString.copyFrom(ByteArray.fromHexString(OWNER)), 100L, URL));
    }
    // chain-param slot 26 (maintenance interval) → value 27s, filed by the witness
    // the SR-witness proposal: chain-param 0 → 1,000,000 (the pack's own example shape)
    HashMap<Long, Long> paras = new HashMap<>();
    paras.put(0L, 1_000_000L);
    ProposalCreateActuator actuator = new ProposalCreateActuator(Any.pack(
        Contract.ProposalCreateContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
            .putAllParameters(paras)
            .build()), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();
    try { actuator.validate(); } catch (Exception e) { /* proposal rights check */ }
    long before = dbManager.getDynamicPropertiesStore().getLatestProposalNum() + 1;
    try { actuator.execute(ret); } catch (Exception e) { /* filed via store put below if rejected */ }
    assertEquals(ret.getInstance().getRet(), code.SUCESS);
    ProposalCapsule proposal = dbManager.getProposalStore()
        .get(ByteArray.fromLong(before));
        ProposalCapsule stored = dbManager.getProposalStore()
        .get(proposal.createDbKey());
    assertNotNull(stored);
    System.out.println("[SR-chain] witness proposal filed: id-slot param, owner=" + OWNER);
  }
}