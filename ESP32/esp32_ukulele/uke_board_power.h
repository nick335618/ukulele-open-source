#pragma once
#include <stdint.h>

// Uke uses separate I2C controllers. Both expanders have address 0x41.
template<class Bus> class UkeBoardPower {
 public:
  enum Charge : uint8_t { Unknown, Inactive, Charging, Trickle, Abnormal };
  enum Infrared : uint8_t { Unavailable, Off, On, Unconfirmed };

  UkeBoardPower(Bus &powerBus, Bus &lightBus) : power(powerBus), light(lightBus) {}

  void begin(uint32_t now) {
    light.begin(25, 26, 100000);
    light.setTimeOut(8);
    // Bring the emitters up before the rest of ESP setup and FPGA warm-up.
    setInfrared(true, now);
    power.begin(32, 33, 100000);
    power.setTimeOut(8);
  }

  bool infraredReady(bool enabled, uint32_t now) {
    const auto wanted = enabled ? On : Off;
    if (infrared != wanted && (enabled || detected) && now - lastLightAttempt >= 100)
      setInfrared(enabled, now);
    if (!detected) return true;  // Previous main/emitter board without the expander.
    return infrared == wanted && (!enabled || now - lightConfirmedAt >= 50);
  }

  void pollInfrared(bool enabled, uint32_t now) {
    if (now - lastLightAttempt >= 2500 && (enabled || detected)) setInfrared(enabled, now);
  }

  void pollBattery(uint32_t now) {
    if (now - chargeAt >= 125) {
      chargeAt = now;
      uint16_t input;
      if (!chargeConfigured) {
        uint16_t configuration, polarity;
        // P0/P1 are charger status inputs. Preserve the unused upper pins.
        // Re-establish direction and polarity after an ESP-only warm restart.
        chargeConfigured = read(power, 0x41, 3, configuration, 1) &&
          read(power, 0x41, 2, polarity, 1) &&
          write(power, 0x41, 3, configuration | 3, 1) &&
          write(power, 0x41, 2, polarity & ~3u, 1) &&
          read(power, 0x41, 3, configuration, 1) && (configuration & 3) == 3 &&
          read(power, 0x41, 2, polarity, 1) && (polarity & 3) == 0;
      }
      if (!chargeConfigured || !read(power, 0x41, 0, input, 1)) {
        chargeConfigured = false;
        if (chargeReadFailures != 65535) ++chargeReadFailures;
        charge = Unknown;
        chargePins = 255;
        haveCharge = false;
        transitions = 0;
      } else {
        chargePins = input & 3;
        const bool led = input & 2;
        if (!haveCharge) {
          haveCharge = true;
          chargeLed = led;
          changedAt = now;
        } else if (led != chargeLed) {
          if (now - changedAt > 2500) transitions = 0;
          if (transitions < 3) ++transitions;
          chargeLed = led;
          changedAt = now;
        }
        if (transitions >= 3 && now - changedAt < 2500) charge = Abnormal;
        else if (now - changedAt >= 2500) {
          transitions = 0;
          charge = !led ? Inactive : (input & 1) ? Charging : Trickle;
        }
      }
    }
    if (now - batteryAt < 1000) return;
    batteryAt = now;
    uint16_t value;
    if (!voltageConfigured) {
      uint16_t die;
      // 16-sample averaging, 1.1 ms conversion, continuous bus voltage only.
      voltageConfigured = read(power, 0x40, 0xfe, value, 2) && value == 0x5449 &&
        read(power, 0x40, 0xff, die, 2) && (die >> 4) == 0x226 &&
        write(power, 0x40, 0, 0x4526, 2);
      return;
    }
    if (!read(power, 0x40, 2, value, 2)) {
      voltageConfigured = false;
      millivolts = 0;
      return;
    }
    const unsigned mv = (unsigned(value) * 5 + 2) / 4;
    // Zero/open sense and out-of-range results are explicitly unknown.
    if (mv < 5000 || mv > 8700) millivolts = 0;
    else millivolts = millivolts ? (3u * millivolts + mv + 2) / 4 : mv;
  }

  static uint8_t percent(unsigned mv) {
    if (!mv) return 255;
    // Approximate 2S Li-ion voltage curve. Calibrate against the installed pack.
    const uint16_t points[] = {6400, 6800, 7100, 7300, 7400, 7520, 7660, 7800, 7960, 8160, 8400};
    if (mv <= points[0]) return 0;
    for (unsigned i = 1; i < 11; ++i) {
      if (mv <= points[i]) return (i - 1) * 10 + (mv - points[i-1]) * 10 / (points[i] - points[i-1]);
    }
    return 100;
  }

  uint16_t millivolts = 0;
  uint8_t chargePins = 255;
  uint16_t chargeReadFailures = 0;
  Charge charge = Unknown;
  Infrared infrared = Unavailable;

 private:
  Bus &power, &light;
  bool detected = false, voltageConfigured = false, haveCharge = false, chargeLed = false;
  bool chargeConfigured = false;
  uint8_t transitions = 0;
  uint32_t batteryAt = 0, chargeAt = 0, changedAt = 0, lastLightAttempt = 0, lightConfirmedAt = 0;

  static bool read(Bus &bus, uint8_t address, uint8_t reg, uint16_t &result, uint8_t length) {
    bus.beginTransmission(address);
    bus.write(reg);
    if (bus.endTransmission(false) || bus.requestFrom(address, length) != length) return false;
    result = bus.read();
    if (length == 2) result = (result << 8) | bus.read();
    return true;
  }
  static bool write(Bus &bus, uint8_t address, uint8_t reg, uint16_t data, uint8_t length) {
    bus.beginTransmission(address);
    bus.write(reg);
    if (length == 2) bus.write(uint8_t(data >> 8));
    bus.write(uint8_t(data));
    return bus.endTransmission() == 0;
  }
  void setInfrared(bool enabled, uint32_t now) {
    lastLightAttempt = now;
    const uint8_t mask = enabled ? 15 : 0;
    uint16_t configuration, pins;
    if (!read(light, 0x41, 3, configuration, 1)) {
      infrared = detected ? Unconfirmed : Unavailable;
      return;
    }
    detected = true;
    const bool ok = (!(configuration & 15) ||
                    (write(light, 0x41, 1, 0, 1) && write(light, 0x41, 3, 0xf0, 1))) &&
                    write(light, 0x41, 1, 0xf0 | mask, 1) &&
                    read(light, 0x41, 0, pins, 1) && (pins & 15) == mask;
    const auto next = !ok ? Unconfirmed : enabled ? On : Off;
    if (infrared != next || (configuration & 15)) lightConfirmedAt = now;
    infrared = next;
  }
};
