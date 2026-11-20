const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const ADMIN_UID = "admin_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function baseUserPayload(uid) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    displayName: "Alice Streamer",
    email: "alice@example.com",
    photoUrl: "https://example.com/a.jpg",
    role: "USER",
    subscriptionPlan: "FREE",
    subscriptionStatus: "ACTIVE",
    accountStatus: "ACTIVE",
    dailyUsedSeconds: 0,
    dailyUsageDate: "2026-11-20",
    storageUsedBytes: 0,
    termsAccepted: true,
    createdAt: now,
    updatedAt: now,
  };
}

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("payments").where("userId", "==", ALICE_UID).get());
  await assertFails(unauthDb.collection("system_settings").doc("global").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(baseUserPayload(ALICE_UID)));
});

test("User registration prevents self-assigned role or paid plan escalation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true }).firestore();

  // Valid FREE/USER registration succeeds
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(baseUserPayload(ALICE_UID)));

  // Escalated role=ADMIN or plan=LIFETIME on create fails
  await assertFails(
    aliceDb.collection("users").doc("evil_user").set({
      ...baseUserPayload("evil_user"),
      role: "ADMIN",
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).set(baseUserPayload(BOB_UID))
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      subscriptionPlan: "LIFETIME",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("User cannot list all users or read/write another user's profile or subcollections", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(BOB_UID).set(baseUserPayload(BOB_UID));
    await ctx.firestore().collection("users").doc(BOB_UID).collection("destinations").doc("dest_1").set({
      userId: BOB_UID,
      name: "Bob YT",
      platform: "YOUTUBE",
      protocol: "RTMPS",
      rtmpUrl: "rtmps://a.rtmp.youtube.com/live2",
      maskedStreamKey: "••••-1234",
      connectionStatus: "CONNECTED",
      supports60Fps: true,
      supports4K: true,
      createdAt: new Date(Date.now() - 10000),
      updatedAt: new Date(Date.now() - 10000),
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("destinations").get());
});

test("Payment workflow: user can submit PENDING payment only; only Admin can verify PAID", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 1000);

  // User trying to self-approve PAID payment fails
  await assertFails(
    aliceDb.collection("payments").doc("pay_cheat").set({
      userId: ALICE_UID,
      userEmail: "alice@example.com",
      transactionId: "TXN999999",
      amount: 1500,
      currency: "BDT",
      paymentMethod: "BKASH",
      plan: "LIFETIME",
      status: "PAID",
      createdAt: now,
      updatedAt: now,
    })
  );

  // User submitting PENDING payment succeeds
  await assertSucceeds(
    aliceDb.collection("payments").doc("pay_1").set({
      userId: ALICE_UID,
      userEmail: "alice@example.com",
      transactionId: "BKS829104",
      amount: 1500,
      currency: "BDT",
      paymentMethod: "BKASH",
      plan: "LIFETIME",
      status: "PENDING",
      createdAt: now,
      updatedAt: now,
    })
  );

  // User updating own payment to PAID fails
  await assertFails(
    aliceDb.collection("payments").doc("pay_1").update({
      status: "PAID",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Scoped list query succeeds, unfiltered query fails
  await assertSucceeds(aliceDb.collection("payments").where("userId", "==", ALICE_UID).get());
  await assertFails(aliceDb.collection("payments").get());

  // Unverified email spoofing as admin fails
  const spoofDb = testEnv.authenticatedContext("spoof_1", {
    email: "kabir020314@gmail.com",
    email_verified: false,
  }).firestore();
  await assertFails(
    spoofDb.collection("payments").doc("pay_1").update({
      status: "PAID",
      verifiedAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Verified admin succeeds
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "kabir020314@gmail.com",
    email_verified: true,
  }).firestore();
  await assertSucceeds(
    adminDb.collection("payments").doc("pay_1").update({
      status: "PAID",
      verifiedAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Stream session terminal status lock and subcollection CRUD parity", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("streams").doc("str_1").set({
      userId: ALICE_UID,
      title: "Tech Keynote Live",
      description: "Testing low latency",
      sourceType: "CAMERA",
      orientation: "LANDSCAPE_16_9",
      aspectMode: "FIT",
      resolution: "1080p",
      fps: 60,
      videoBitrateKbps: 6000,
      audioBitrateKbps: 128,
      destinationNames: ["YouTube"],
      status: "LIVE",
      durationSeconds: 0,
      plannedDurationSeconds: 10800,
      loopMode: "ONCE",
      droppedFramesPercent: 0.0,
      connectionQuality: "EXCELLENT",
      workerStatus: "DEVICE_ACTIVE",
      workerCpuPercent: 22,
      workerRamPercent: 30,
      reconnectAttempts: 0,
      dataTransferredMb: 0.0,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("streams").doc("str_1").update({
      status: "COMPLETED",
      durationSeconds: 320,
      dataTransferredMb: 185.5,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Terminal status locking prevents client from reopening a COMPLETED stream
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("streams").doc("str_1").update({
      status: "LIVE",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Subcollection list works even when empty
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("schedules").get()
  );
});
