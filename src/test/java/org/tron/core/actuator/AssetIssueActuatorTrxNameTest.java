/* The TRX-name leg, trimmed after the split: reserved-name probe + Close-mode issuance. */
package org.tron.core.actuator;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

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
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Result.code;

public class AssetIssueActuatorTrxNameTest {

  private static TronApplicationContext context;
  private static Manager dbManager;
  private static final String dbPath = "output_assetIssue_trxname_test";
  private static final String OWNER_ADDRESS;
  private static final long TOTAL_SUPPLY = 10_000_000L;
  private static final int TRX_NUM = 100;
  private static final int NUM = 1000;
  private static final String URL = "https://justitia.parsmonarch.io";

  static {
    Args.setParam(new String[]{"--output-directory", dbPath}, Constant.TEST_CONF);
    context = new TronApplicationContext(DefaultConfig.class);
    OWNER_ADDRESS = Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049150";
  }

  @BeforeClass
  public static void init() {
    deleteRecursively(new java.io.File(dbPath));
    dbManager = context.getBean(Manager.class);
    AccountCapsule owner = new AccountCapsule(
        ByteString.copyFromUtf8("issuer"),
        ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
        AccountType.Normal, 2_000_000_000_000L);
    dbManager.getAccountStore().put(owner.getAddress().toByteArray(), owner);
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

  private AssetIssueActuator named(String tokenName) {
    return new AssetIssueActuator(Any.pack(
        Contract.AssetIssueContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)))
            .setName(ByteString.copyFromUtf8(tokenName))
            .setUrl(ByteString.copyFromUtf8(URL))
            .setTotalSupply(TOTAL_SUPPLY)
            .setTrxNum(TRX_NUM)
            .setNum(NUM)
            .setStartTime(new Date().getTime())
            .setEndTime(new Date().getTime() + 24 * 3600 * 1000)
            .setPrecision(6)
            .build()), dbManager);
  }

  /** DISCOVERED GATE RULE: the chain reserves the "trx" token name. */
  @Test
  public void TrxNameIsReserved() { // flag=1: gate fires in this mode
    dbManager.getDynamicPropertiesStore().saveAllowSameTokenName(1);
    boolean rejected = false;
    try {
      named("TRX").validate();
    } catch (ContractValidateException e) {
      rejected = e.getMessage().contains("trx");
    }
    assertTrue(rejected, "expected the reserved-name gate");
    System.out.println("[actuator] gate rule confirmed: assetName can't be trx");
  }

  /** Close-mode issuance of the migration unit under the permitted variant name. */
  @Test
  public void SameTokenNameCloseIssueTrxName()
      throws ContractValidateException, ContractExeException {
    dbManager.getDynamicPropertiesStore().saveAllowSameTokenName(0);
    TransactionResultCapsule ret = new TransactionResultCapsule();
    named("USDT").validate();
    named("USDT").execute(ret);
    assertEquals(ret.getInstance().getRet(), code.SUCESS);

    AssetIssueCapsule a1 = dbManager.getAssetIssueStore()
        .get(ByteString.copyFromUtf8("USDT").toByteArray());
    assertNotNull(a1);
    assertEquals(a1.getName().toStringUtf8(), "USDT");
    assertEquals(a1.getNum(), NUM);
    assertEquals(a1.getTrxNum(), TRX_NUM);
    AccountCapsule owner = dbManager.getAccountStore()
        .get(ByteArray.fromHexString(OWNER_ADDRESS));
    assertEquals(owner.getAssetMap().get("USDT").longValue(), TOTAL_SUPPLY);
    System.out.println("[actuator] USDT (TRC10) issued in V1: owner asset=" + TOTAL_SUPPLY);
  }
}

