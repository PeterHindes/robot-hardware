// ============================================================================
// Nano Hub Controller Firmware for Arduino Nano v3.0 (ATmega328P)
// Supports interactive serial commands & real-time telemetry with Android app
// ============================================================================

#ifndef FW_VERSION
#define FW_VERSION "1.0.0"
#endif

#ifndef DEFAULT_BLINK_MS
#define DEFAULT_BLINK_MS 500
#endif

static const uint8_t BOARD_LED_PIN = LED_BUILTIN; // Pin 13 on Nano v3.0 & UNO R4 Minima

enum LedMode {
  MODE_BLINK = 0,
  MODE_MANUAL = 1
};

static LedMode currentMode = MODE_BLINK;
static unsigned long blinkIntervalMs = DEFAULT_BLINK_MS;
static unsigned long lastBlinkMs = 0;
static bool ledState = false;

static bool streamEnabled = false;
static unsigned long streamIntervalMs = 250;
static unsigned long lastStreamMs = 0;

static String inputBuffer = "";

void printStatusJson() {
  Serial.print(F("{\"type\":\"status\",\"fw\":\""));
  Serial.print(F(FW_VERSION));
#ifdef ARDUINO_UNOR4_MINIMA
  Serial.print(F("\",\"board\":\"Arduino UNO R4 Minima\",\"mode\":\""));
#else
  Serial.print(F("\",\"board\":\"Arduino Nano v3.0\",\"mode\":\""));
#endif
  Serial.print(currentMode == MODE_BLINK ? F("BLINK") : F("MANUAL"));
  Serial.print(F("\",\"led\":"));
  Serial.print(ledState ? 1 : 0);
  Serial.print(F(",\"blinkMs\":"));
  Serial.print(blinkIntervalMs);
  Serial.print(F(",\"stream\":"));
  Serial.print(streamEnabled ? 1 : 0);
  Serial.print(F(",\"uptimeMs\":"));
  Serial.print(millis());
  Serial.print(F(",\"a0\":"));
  Serial.print(analogRead(A0));
  Serial.print(F(",\"a1\":"));
  Serial.print(analogRead(A1));
  Serial.print(F(",\"a2\":"));
  Serial.print(analogRead(A2));
  Serial.print(F(",\"a3\":"));
  Serial.print(analogRead(A3));
  Serial.println(F("}"));
}

void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.length() == 0) return;

  String upper = cmd;
  upper.toUpperCase();

  if (upper == "PING") {
    Serial.print(F("PONG fw="));
    Serial.print(F(FW_VERSION));
    Serial.print(F(" uptime="));
    Serial.println(millis());
  } else if (upper == "INFO" || upper == "STATUS") {
    printStatusJson();
  } else if (upper == "LED ON") {
    currentMode = MODE_MANUAL;
    ledState = true;
    digitalWrite(BOARD_LED_PIN, HIGH);
    printStatusJson();
  } else if (upper == "LED OFF") {
    currentMode = MODE_MANUAL;
    ledState = false;
    digitalWrite(BOARD_LED_PIN, LOW);
    printStatusJson();
  } else if (upper == "LED TOGGLE") {
    currentMode = MODE_MANUAL;
    ledState = !ledState;
    digitalWrite(BOARD_LED_PIN, ledState ? HIGH : LOW);
    printStatusJson();
  } else if (upper.startsWith("BLINK ")) {
    long ms = upper.substring(6).toInt();
    if (ms >= 25 && ms <= 10000) {
      blinkIntervalMs = (unsigned long)ms;
      currentMode = MODE_BLINK;
      printStatusJson();
    } else {
      Serial.println(F("{\"type\":\"error\",\"msg\":\"BLINK interval must be 25..10000 ms\"}"));
    }
  } else if (upper == "STREAM ON") {
    streamEnabled = true;
    printStatusJson();
  } else if (upper == "STREAM OFF") {
    streamEnabled = false;
    printStatusJson();
  } else if (upper.startsWith("PWM ")) {
    int firstSpace = upper.indexOf(' ', 4);
    if (firstSpace > 4) {
      int pin = upper.substring(4, firstSpace).toInt();
      int val = upper.substring(firstSpace + 1).toInt();
      if (pin >= 2 && pin <= 13 && val >= 0 && val <= 255) {
        if (pin == BOARD_LED_PIN) currentMode = MODE_MANUAL;
        pinMode(pin, OUTPUT);
        analogWrite(pin, val);
        Serial.print(F("{\"type\":\"ack\",\"cmd\":\"PWM\",\"pin\":"));
        Serial.print(pin);
        Serial.print(F(",\"val\":"));
        Serial.print(val);
        Serial.println(F("}"));
      }
    }
  } else if (upper.startsWith("PIN ")) {
    int firstSpace = upper.indexOf(' ', 4);
    if (firstSpace > 4) {
      int pin = upper.substring(4, firstSpace).toInt();
      String action = upper.substring(firstSpace + 1);
      action.trim();
      if (pin >= 2 && pin <= 13) {
        if (action == "HIGH" || action == "1") {
          if (pin == BOARD_LED_PIN) {
            currentMode = MODE_MANUAL;
            ledState = true;
          }
          pinMode(pin, OUTPUT);
          digitalWrite(pin, HIGH);
          printStatusJson();
        } else if (action == "LOW" || action == "0") {
          if (pin == BOARD_LED_PIN) {
            currentMode = MODE_MANUAL;
            ledState = false;
          }
          pinMode(pin, OUTPUT);
          digitalWrite(pin, LOW);
          printStatusJson();
        } else if (action == "READ") {
          pinMode(pin, INPUT_PULLUP);
          int v = digitalRead(pin);
          Serial.print(F("{\"type\":\"pin\",\"pin\":"));
          Serial.print(pin);
          Serial.print(F(",\"val\":"));
          Serial.print(v);
          Serial.println(F("}"));
        }
      }
    }
  } else if (upper == "HELP") {
    Serial.println(F("Commands: PING | INFO | LED ON | LED OFF | LED TOGGLE | BLINK <ms> | STREAM ON | STREAM OFF | PIN <2-13> <HIGH|LOW|READ> | PWM <pin> <0-255>"));
  } else {
    Serial.print(F("{\"type\":\"echo\",\"cmd\":\""));
    Serial.print(cmd);
    Serial.println(F("\"}"));
  }
}

void setup() {
  pinMode(BOARD_LED_PIN, OUTPUT);
  digitalWrite(BOARD_LED_PIN, LOW);
  Serial.begin(115200);
  inputBuffer.reserve(64);
  delay(50);
  printStatusJson();
}

void loop() {
  unsigned long now = millis();

  if (currentMode == MODE_BLINK && (now - lastBlinkMs >= blinkIntervalMs)) {
    lastBlinkMs = now;
    ledState = !ledState;
    digitalWrite(BOARD_LED_PIN, ledState ? HIGH : LOW);
  }

  if (streamEnabled && (now - lastStreamMs >= streamIntervalMs)) {
    lastStreamMs = now;
    printStatusJson();
  }

  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (inputBuffer.length() > 0) {
        handleCommand(inputBuffer);
        inputBuffer = "";
      }
    } else if (inputBuffer.length() < 60) {
      inputBuffer += c;
    }
  }
}
