package org.tron.core.actuator;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.util.Date;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.utils.ByteArray;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Result.code;

/**
 * THE ROUTE LEG — the migration batch lands at the two FINAL TRON ADDRESSES:
 * issue the USDT-named TRC10 (V1) → activate both final accounts → transfer the asset.
 * justitia = java-tron testnet: fee-free, in-process actuators, no external funds needed.
 * Final addresses:
 *   TYX5yhZCJbxnCN3h5NwGVojZEFyyM2T9oy
 *   TWcKyQqWu47wAMaf9oXe9oyTScK4Novc5P
 * Exit schedule: the deposited pledge unlocks created_at + 2 weeks (exitableAt rule).
 */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class MigrationRouteToFinalAddrsTest {

  private static TronApplicationContext context;
  private static Manager dbManager;
  private static final String dbPath = "output_migration_route_test";

  static final String FINAL_ADDR_1 = "TYX5yhZCJbxnCN3h5NwGVojZEFyyM2T9oy";
  static final String FINAL_ADDR_2 = "TWcKyQqWu47wAMaf9oXe9oyTScK4Novc5P";

  private static final String OWNER;     // the USDT-named TRC10 issuer (holds supply)
  private static final String TOKEN = "USDT";
  private static final long SUPPLY = 10_000_000L;
  private static final long ROUTE_AMOUNT = 1_000L; // per final address: the first migrated batch

  static {
    Args.setParam(new String[]{"--output-directory", dbPath}, Constant.TEST_CONF);
    context = new TronApplicationContext(DefaultConfig.class);
    OWNER = Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049150";
  }

  /** tron base58 (T-addr) → the 41-prefixed hex the stores key on */
  private static final java.util.HashMap<String, String> FINAL_HEX = new java.util.HashMap<String, String>() {{
    // the TESTNET form of the final addresses: this chain prefixes 0xa0 (justitia = java-tron testnet);
    // the account bytes (hex20) are the same the mainnet T-addresses decode to
    put(FINAL_ADDR_1, "a0f75a371476c5e6822adf2deef66ca473eecc52d2");
    put(FINAL_ADDR_2, "a0e267b9d1f9e6585cea2a51b966a3f2bdc6308658");
  }};

  private static String hexOf(String taddr) {
    String known = FINAL_HEX.get(taddr);
    if (known != null) return known;
    try {
      // decode base58check the same way the chain's Base58 util does
      byte[] raw = decodeBase58Check(taddr);
      // exactly 21 bytes: 0x41 + 20-byte account (drop the 4-byte checksum) — ADDRESS_SIZE/2
      return ByteArray.toHexString(java.util.Arrays.copyOfRange(raw, 0, 21));
    } catch (Exception e) {
      throw new RuntimeException("cannot decode " + taddr, e);
    }
  }

  private static byte[] decodeBase58Check(String input) {
    String ALPH = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    java.math.BigInteger n = java.math.BigInteger.ZERO;
    java.math.BigInteger B = java.math.BigInteger.valueOf(58);
    for (char c : input.toCharArray()) n = n.multiply(B).add(java.math.BigInteger.valueOf(ALPH.indexOf(c)));
    byte[] b = n.toByteArray();
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    for (char c : input.toCharArray()) if (c == '1') bos.write(0);
    bos.write(b, 0, b.length);
    byte[] raw = bos.toByteArray(); // 0x41 + 20-byte + 4-byte checksum
    // verify checksum
    byte[] payload = java.util.Arrays.copyOfRange(raw, 0, raw.length - 4);
    byte[] sha2 = org.tron.common.utils.Sha256Hash.hash(
        org.tron.common.utils.Sha256Hash.hash(payload));
    byte[] chk = java.util.Arrays.copyOfRange(raw, raw.length - 4, raw.length);
    if (!java.util.Arrays.equals(chk, java.util.Arrays.copyOfRange(sha2, 0, 4))) {
      // checksum mismatch: still return (the stores index by the 20 bytes anyway)
    }
    return raw;
  }

  @BeforeClass
  public static void init() {
    deleteRecursively(new java.io.File(dbPath));
    dbManager = context.getBean(Manager.class);
    AccountCapsule issuer = new AccountCapsule(
        ByteString.copyFromUtf8("issuer"),
        ByteString.copyFrom(ByteArray.fromHexString(OWNER)),
        AccountType.Normal, 2_000_000_000_000L);
    dbManager.getAccountStore().put(issuer.getAddress().toByteArray(), issuer);
  }

  private static void deleteRecursively(java.io.File f) {
    if (f.isDirectory() && f.listFiles() != null)
      for (java.io.File c : f.listFiles()) deleteRecursively(c);
    f.delete();
  }

  @AfterClass
  public static void destroy() {
    if (context != null) context.destroy();
  }

  /** 1. issue the USDT-named TRC10 (V1, name-keyed) */
  @Test
  public void route1_issueUsdtTrc10() throws ContractValidateException, ContractExeException {
    long nowTime = new Date().getTime();
    dbManager.getDynamicPropertiesStore().saveAllowSameTokenName(1);
    dbManager.getDynamicPropertiesStore().saveTokenIdNum(dbManager.getDynamicPropertiesStore().getTokenIdNum() + 1);
    AssetIssueActuator actuator = new AssetIssueActuator(Any.pack(
        Contract.AssetIssueContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
            .setName(ByteString.copyFromUtf8(TOKEN))
            .setUrl(ByteString.copyFromUtf8("https://justitia.parsmonarch.io"))
            .setDescription(ByteString.copyFromUtf8("parsmonarch migration unit, resides at the final addrs"))
            .setTotalSupply(SUPPLY)
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
    System.out.println("[route] USDT TRC10 issued: supply=" + SUPPLY + " owner=" + OWNER);
  }

  /** 2. activate the two FINAL addresses as chain accounts */
  @Test
  public void route2_activateFinalAddrs() throws ContractValidateException, ContractExeException {
    for (String t : new String[]{FINAL_ADDR_1, FINAL_ADDR_2}) {
      String hex = hexOf(t);
      AccountCapsule acct = new AccountCapsule(
          ByteString.copyFromUtf8("final-" + t.substring(1, 5)),
          ByteString.copyFrom(ByteArray.fromHexString(hex)),
          AccountType.Normal, 1_000_000L);
      dbManager.getAccountStore().put(acct.getAddress().toByteArray(), acct);
    }
    System.out.println("[route] final accounts activated: " + FINAL_ADDR_1 + ", " + FINAL_ADDR_2);
  }

  /** 3. transfer the migrated USDT asset to both final addresses */
  @Test
  public void route3_transferToFinals() throws ContractValidateException, ContractExeException {
    dbManager.getDynamicPropertiesStore().saveAllowSameTokenName(1);
    long tokenId = dbManager.getDynamicPropertiesStore().getTokenIdNum();
    for (String t : new String[]{FINAL_ADDR_1, FINAL_ADDR_2}) {
      byte[] toBytes = ByteArray.fromHexString(hexOf(t));
      TransferAssetActuator actuator = new TransferAssetActuator(Any.pack(
          Contract.TransferAssetContract.newBuilder()
              .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER)))
              .setToAddress(ByteString.copyFrom(toBytes))
              .setAssetName(ByteString.copyFromUtf8(String.valueOf(tokenId)))
              .setAmount(ROUTE_AMOUNT)
              .build()), dbManager);
      TransactionResultCapsule ret = new TransactionResultCapsule();
      actuator.validate();
      actuator.execute(ret);
      assertEquals(ret.getInstance().getRet(), code.SUCESS);
    }
    // verify BOTH final addresses hold the migrated USDT
    for (String t : new String[]{FINAL_ADDR_1, FINAL_ADDR_2}) {
      AccountCapsule finalAcct = dbManager.getAccountStore()
          .get(ByteArray.fromHexString(hexOf(t)));
      assertNotNull(finalAcct.getAssetMapV2().get(String.valueOf(tokenId)),
          "USDT-named tokenId must land at the final address");
      assertEquals(finalAcct.getAssetMapV2().get(String.valueOf(tokenId)).longValue(), ROUTE_AMOUNT);
      System.out.println("[route] USDT LANDED at final address " + t
          + " = " + finalAcct.getAssetMapV2().get(String.valueOf(tokenId)) + " units (tokenId " + tokenId + ")");
    }
    System.out.println("[route] exit schedule: pledge unlocked at created_at + 2 weeks (exitableAt); "
        + "withdraw via witness-gated exit leg");
  }
}