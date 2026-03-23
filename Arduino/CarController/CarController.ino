// ================== PIN DEFINITIONS ==================
#define leftSensorPin A0
#define rightSensorPin A1
#define ENA 13
#define IN1 11
#define IN2 10
#define ENB 12
#define IN3 9
#define IN4 8

// ===== ULTRASONIDO (HC-SR04) =====
#define TRIG_PIN 6
#define ECHO_PIN 7

const int DIST_STOP_CM = 35;             // distancia de frenado en cm
const unsigned long US_TIMEOUT = 25000;  // microsegundos (~25ms)
const unsigned long US_COOLDOWN_MS = 60; // cada cuánto iniciar una medición (ms)

// ================== REPLAY STRUCTS ==================
const int REPLAY_BUF_MAX = 120;

struct Block {
  char buf[REPLAY_BUF_MAX];
  int len;
  int idx;
  long seq;
  unsigned long tickMs;
  bool valid;
};

// ================== PROTOTIPOS ==================
void procesarComando(String cmd);

void ultrasonicUpdate();
bool obstacleTooClose();
bool cmdNeedsUltrasound(char c);

void emergencyBackOffAndStop();
void emergencyManual();
void emergencyAuto();
void emergencyReplay();

void handleIncomingBlock(long seq, unsigned long tickMs, const String &payload);
void loadBlock(Block &b, long seq, unsigned long tickMs, const String &payload, int len);
void requestNext(long seq);
void clearReplay();

void seguirlinea_tick();

void appendSaveTick(char cmd);
void flushSaveBlock();
void sendSaveBlock(unsigned long seqStart, char* payload, int len);

void instruccionChar(char cmd);
void moveForward();
void moveBackward();
void turnLeftArc();
void turnRightArc();
void stopMoving();

// ================== ULTRASONIDO NO BLOQUEANTE ==================
int lastDistCm = 999;

enum UsState { US_IDLE, US_TRIG_LOW, US_TRIG_HIGH, US_WAIT_RISE, US_WAIT_FALL };
UsState usState = US_IDLE;

unsigned long usNextStartMs = 0;
unsigned long usT0 = 0;
unsigned long usEchoRiseUs = 0;

static inline void usFinish(unsigned long nowMs, int cm) {
  lastDistCm = cm;
  usState = US_IDLE;
  usNextStartMs = nowMs + US_COOLDOWN_MS;
}

void ultrasonicUpdate() {
  unsigned long nowMs = millis();
  unsigned long nowUs = micros();

  switch (usState) {

    case US_IDLE: {
      if ((long)(nowMs - usNextStartMs) >= 0) {
        digitalWrite(TRIG_PIN, LOW);
        usT0 = nowUs;
        usState = US_TRIG_LOW;
      }
    } break;

    case US_TRIG_LOW: {
      if ((unsigned long)(nowUs - usT0) >= 2) {
        digitalWrite(TRIG_PIN, HIGH);
        usT0 = nowUs;
        usState = US_TRIG_HIGH;
      }
    } break;

    case US_TRIG_HIGH: {
      if ((unsigned long)(nowUs - usT0) >= 10) {
        digitalWrite(TRIG_PIN, LOW);
        usT0 = nowUs; // inicio espera subida
        usState = US_WAIT_RISE;
      }
    } break;

    case US_WAIT_RISE: {
      if (digitalRead(ECHO_PIN) == HIGH) {
        usEchoRiseUs = nowUs;
        usState = US_WAIT_FALL;
      } else if ((unsigned long)(nowUs - usT0) >= US_TIMEOUT) {
        usFinish(nowMs, 999);
      }
    } break;

    case US_WAIT_FALL: {
      if (digitalRead(ECHO_PIN) == LOW) {
        unsigned long dur = (unsigned long)(nowUs - usEchoRiseUs);
        int cm = (int)(dur / 58UL);
        if (cm <= 0) cm = 1;
        usFinish(nowMs, cm);
      } else if ((unsigned long)(nowUs - usEchoRiseUs) >= US_TIMEOUT) {
        usFinish(nowMs, 999);
      }
    } break;
  }
}

bool obstacleTooClose() {
  // Consulta instantánea
  return lastDistCm <= DIST_STOP_CM;
}

// ================== AUTO (SEGUIR LÍNEA + GUARDAR) ==================
const unsigned long AUTO_TICK_MS = 15;
bool modoSeguirLinea = false;
bool guardarRuta = false;
String ultimoGiro = "";

unsigned long lastAutoTick = 0;
unsigned long tickCounter = 0;

// ---- Guardado en bloques ----
const int SAVE_BLOCK_SIZE = 20;
const int SAVE_REPEAT = 3;

char saveBuf[SAVE_BLOCK_SIZE + 1];
int savePos = 0;
unsigned long saveBlockStartSeq = 0;

// ================== REPLAY DOBLE BUFFER ==================
Block cur;
Block nextB;

bool replayActive = false;
unsigned long replayNextTick = 0;

long expectedNextSeq = 0;
bool startedReplay = false;

// RX
String rxLine = "";

// ================== MANUAL ==================
bool manualActivo = false;
char ultimoManual = 0;

// bloqueo de replay tras obstáculo
bool replayBlockedByObstacle = false;

// ================== HELPERS SAFETY ==================
bool cmdNeedsUltrasound(char c) {
  return (c == 'W' || c == 'A' || c == 'D');
}

void emergencyBackOffAndStop() {
  moveBackward();
  delay(150);
  stopMoving();

  Serial.print("OBS,");
  Serial.println(lastDistCm);
}

void emergencyManual() {
  emergencyBackOffAndStop();
  ultimoManual = 0;
}

void emergencyAuto() {
  if (guardarRuta) flushSaveBlock();
  modoSeguirLinea = false;
  guardarRuta = false;
  emergencyBackOffAndStop();
}

void emergencyReplay() {
  replayBlockedByObstacle = true;
  emergencyBackOffAndStop();
  clearReplay();
  stopMoving();
}

// ================== SETUP / LOOP ==================
void setup() {
  Serial.begin(9600);

  pinMode(ENA, OUTPUT);
  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(ENB, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);

  pinMode(leftSensorPin, INPUT);
  pinMode(rightSensorPin, INPUT);

  // Ultrasonido
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  digitalWrite(TRIG_PIN, LOW);
  usNextStartMs = millis(); // primera medición

  analogWrite(ENA, 130);
  analogWrite(ENB, 130);

  clearReplay();
  stopMoving();
}

void loop() {
  // Update ultrasonido NO bloqueante
  ultrasonicUpdate();

  // RX lines
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (rxLine.length() > 0) {
        procesarComando(rxLine);
        rxLine = "";
      }
    } else {
      rxLine += c;
    }
  }

  // AUTO tick
  if (modoSeguirLinea) {
    unsigned long now = millis();
    if (now - lastAutoTick >= AUTO_TICK_MS) {
      lastAutoTick = now;
      seguirlinea_tick();
    }
  }

  // MANUAL vigilancia (W/A/D) y se desarma tras emergencia
  if (manualActivo && cmdNeedsUltrasound(ultimoManual)) {
    if (obstacleTooClose()) {
      emergencyManual();
    }
  }

  // REPLAY tick
  if (replayActive && cur.valid) {
    unsigned long now = millis();
    if ((long)(now - replayNextTick) >= 0) {

      if (cur.idx < cur.len) {
        char cPeek = cur.buf[cur.idx];

        if (cmdNeedsUltrasound(cPeek) && obstacleTooClose()) {
          emergencyReplay();
          return;
        }

        char c = cur.buf[cur.idx++];

        if (c == 'W' || c == 'A' || c == 'S' || c == 'D') {
          instruccionChar(c);
        }
        replayNextTick = now + cur.tickMs;

      } else {
        Serial.print("ACK,");
        Serial.println(cur.seq);

        if (nextB.valid) {
          cur = nextB;
          nextB.valid = false;

          expectedNextSeq = cur.seq + cur.len;
          requestNext(expectedNextSeq);

          replayNextTick = now + cur.tickMs;
          replayActive = true;
        } else {
          stopMoving();
          replayActive = false;

          expectedNextSeq = cur.seq + cur.len;
          requestNext(expectedNextSeq);
        }
      }
    }
  }
}

// ================== COMMAND PARSER ==================
void procesarComando(String cmd) {
  cmd.trim();

  // STOP universal
  if (cmd.equalsIgnoreCase("STOP")) {
    if (modoSeguirLinea && guardarRuta) flushSaveBlock();

    modoSeguirLinea = false;
    guardarRuta = false;

    manualActivo = false;
    ultimoManual = 0;

    replayBlockedByObstacle = false;

    clearReplay();
    stopMoving();
    Serial.println("ACK,STOP");
    return;
  }

  // START AUTO: "2" o "2G"
  if (cmd.startsWith("2")) {
    replayBlockedByObstacle = false;

    modoSeguirLinea = true;
    guardarRuta = (cmd.indexOf('G') >= 0);

    manualActivo = false;
    ultimoManual = 0;

    ultimoGiro = "";
    tickCounter = 0;
    lastAutoTick = millis();

    savePos = 0;
    saveBlockStartSeq = 0;

    clearReplay();
    stopMoving();
    return;
  }

  // REPLAY BLOQUE: B,tickMs,seq,payload
  if (cmd.startsWith("B,")) {
    if (replayBlockedByObstacle) {
      stopMoving();
      return;
    }

    modoSeguirLinea = false;
    guardarRuta = false;

    manualActivo = false;
    ultimoManual = 0;

    int c1 = cmd.indexOf(',');
    int c2 = cmd.indexOf(',', c1 + 1);
    int c3 = cmd.indexOf(',', c2 + 1);
    if (c1 < 0 || c2 < 0 || c3 < 0) return;

    String tickStr = cmd.substring(c1 + 1, c2);
    String seqStr  = cmd.substring(c2 + 1, c3);
    String payload = cmd.substring(c3 + 1);
    payload.trim();

    unsigned long t = (unsigned long)tickStr.toInt();
    if (t < 5) t = 5;
    if (t > 50) t = 50;

    long seq = seqStr.toInt();

    handleIncomingBlock(seq, t, payload);

    Serial.print("RCV,");
    Serial.println(seq);
    return;
  }

  // Manual simple (W/A/S/D)
  if (cmd.length() == 1) {
    modoSeguirLinea = false;
    guardarRuta = false;

    clearReplay();

    char c = cmd[0];
    manualActivo = true;
    ultimoManual = c;

    if (cmdNeedsUltrasound(c) && obstacleTooClose()) {
      emergencyManual();
      return;
    }

    instruccionChar(c);
  }
}

// ================== REPLAY HANDLING ==================
void handleIncomingBlock(long seq, unsigned long tickMs, const String &payload) {
  int len = payload.length();
  if (len <= 0) return;
  if (len > REPLAY_BUF_MAX) len = REPLAY_BUF_MAX;

  if (!startedReplay) {
    startedReplay = true;

    loadBlock(cur, seq, tickMs, payload, len);
    expectedNextSeq = cur.seq + cur.len;

    replayActive = true;
    replayNextTick = millis();

    requestNext(expectedNextSeq);
    return;
  }

  if (cur.valid && seq == cur.seq) return;
  if (nextB.valid && seq == nextB.seq) return;

  if (seq == expectedNextSeq) {
    loadBlock(nextB, seq, tickMs, payload, len);
    return;
  }
}

void loadBlock(Block &b, long seq, unsigned long tickMs, const String &payload, int len) {
  b.seq = seq;
  b.tickMs = tickMs;
  b.len = len;
  b.idx = 0;
  for (int i = 0; i < len; i++) b.buf[i] = payload[i];
  b.valid = true;
}

void requestNext(long seq) {
  if (replayBlockedByObstacle) return;
  Serial.print("REQ,");
  Serial.println(seq);
}

void clearReplay() {
  cur.valid = false; cur.len = 0; cur.idx = 0; cur.seq = 0; cur.tickMs = 15;
  nextB.valid = false; nextB.len = 0; nextB.idx = 0; nextB.seq = 0; nextB.tickMs = 15;
  replayActive = false;
  expectedNextSeq = 0;
  startedReplay = false;
}

// ================== AUTO LINE FOLLOW ==================
void seguirlinea_tick() {
  if (obstacleTooClose()) {
    emergencyAuto();
    return;
  }

  int leftSensorValue = analogRead(leftSensorPin);
  int rightSensorValue = analogRead(rightSensorPin);

  bool leftOnLine  = leftSensorValue < 512;
  bool rightOnLine = rightSensorValue < 512;

  char cmd;

  if (leftOnLine && rightOnLine) {
    cmd = (ultimoGiro.length() > 0) ? ultimoGiro[0] : 'W';
  } else if (leftOnLine && !rightOnLine) {
    cmd = 'D'; ultimoGiro = "D";
  } else if (!leftOnLine && rightOnLine) {
    cmd = 'A'; ultimoGiro = "A";
  } else {
    cmd = 'W';
  }

  instruccionChar(cmd);

  if (guardarRuta) appendSaveTick(cmd);
}

// ================== GUARDADO BLOQUES ==================
void appendSaveTick(char cmd) {
  if (savePos == 0) saveBlockStartSeq = tickCounter;

  if (savePos < SAVE_BLOCK_SIZE) saveBuf[savePos++] = cmd;

  tickCounter++;

  if (savePos >= SAVE_BLOCK_SIZE) {
    sendSaveBlock(saveBlockStartSeq, saveBuf, savePos);
    savePos = 0;
  }
}

void flushSaveBlock() {
  if (savePos > 0) {
    sendSaveBlock(saveBlockStartSeq, saveBuf, savePos);
    savePos = 0;
  }
}

void sendSaveBlock(unsigned long seqStart, char* payload, int len) {
  for (int k = 0; k < SAVE_REPEAT; k++) {
    Serial.print("G,");
    Serial.print(seqStart);
    Serial.print(",");
    for (int i = 0; i < len; i++) Serial.print(payload[i]);
    Serial.println();
    delay(2);
  }
}

// ================== MOVIMIENTO ==================
void instruccionChar(char cmd) {
  if (cmd == 'W') moveForward();
  else if (cmd == 'S') moveBackward();
  else if (cmd == 'A') turnLeftArc();
  else if (cmd == 'D') turnRightArc();
}

void moveForward() {
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, HIGH);
  digitalWrite(IN1, HIGH);
  digitalWrite(IN2, LOW);
}

void moveBackward() {
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, HIGH);
  digitalWrite(IN3, HIGH);
  digitalWrite(IN4, LOW);
}

void turnLeftArc() {
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, HIGH);
}

void turnRightArc() {
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, LOW);
  digitalWrite(IN1, HIGH);
  digitalWrite(IN2, LOW);
}

void stopMoving() {
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, LOW);
}