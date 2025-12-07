/*
 * Tachometer
 *
 * Use work of InterlinkKnight from https://www.youtube.com/watch?v=u2uJMJWsfsg.
 */

#include <Arduino_BMI270_BMM150.h>
#include <Wire.h>

const int I2C_ADDRESS = 1;
const int INTERRUPT_PIN_1 = 2;
const int INTERRUPT_PIN_2 = 3;
const int PULSES_PER_REVOLUTION = 1;  // Set how many pulses there are on each revolution. Default: 2.

// If the period between pulses is too high, or even if the pulses stopped, then we would get stuck showing the
// last value instead of a 0. Because of this we are going to set a limit for the maximum period allowed.
// If the period is above this value, the rpm will show as 0.
// The higher the set value, the longer lag/delay will have to sense that pulses stopped, but it will allow readings
// at very low rpm.
// Setting a low value is going to allow the detection of stop situations faster, but it will prevent having low rpm readings.
// The unit is in microseconds.
const unsigned long ZERO_TIMEOUT = 100000;  // For high response time, a good value would be 100000.
                                            // For reading very low rpm, a good value would be 300000.

const int NUM_RPM_READINGS = 10;  // Number of samples for smoothing. The higher, the more smoothing, but it's going to
                                  // react slower to changes. 1 = no smoothing. Default: 2.

float xOffset = 0;
float yOffset = 0;

int8_t x = 0;
int8_t y = 0;

uint8_t bytes[4];

// Sensors

volatile unsigned long lastMeasureTimes[2];  // Stores the last time we measured a pulse so we can calculate the period.

volatile unsigned long periodsBetweenPulses[2] = { ZERO_TIMEOUT + 1000, ZERO_TIMEOUT + 1000 };  // Stores the period between pulses in microseconds.
                                                                                                // It has a big number so it doesn't start with 0 which would be interpreted as a high frequency.

volatile unsigned long periodAverages[2] = { ZERO_TIMEOUT + 1000, ZERO_TIMEOUT + 1000 };  // Stores the period between pulses in microseconds in total, if we are taking multiple pulses.
                                                                                          // It has a big number so it doesn't start with 0 which would be interpreted as a high frequency.

volatile unsigned int pulseCounters[2] = { 1, 1 };  // Counts the amount of pulse readings we took so we can average multiple pulses before calculating the period.

volatile unsigned long periodSums[2];  // Stores the sums of all the periods to do the average.

volatile unsigned int amountsOfRpmReadings[2] = { 1, 1 };  // We get the rpm by measuring the time between 2 or more pulses so the following will set how many pulses to
                                                           // take before calculating the rpm. 1 would be the minimum giving a result every pulse, which would feel very responsive
                                                           // even at very low speeds but also is going to be less accurate at higher speeds.
                                                           // With a value around 10 you will get a very accurate result at high speeds, but readings at lower speeds are going to be
                                                           // farther from eachother making it less "real time" at those speeds.
                                                           // There's a function that will set the value depending on the speed so this is done automatically.

unsigned long frequencies[2];  // Calculated frequency, based on the period. This has a lot of extra decimals without the decimal point.

unsigned long rpm[2];  // Raw rpm without any processing.

unsigned long lastCycleMeasureTimes[2];  // Stores the last times we measure a pulse in cycles.
                                         // We need a variable with a value that is not going to be affected by the interrupt
                                         // because we are going to do math and functions that are going to mess up if the values
                                         // changes in the middle of the cycle.

unsigned long currentMicros[2] = { micros(), micros() };  // Stores the micros in that cycle.
                                                          // We need a variable with a value that is not going to be affected by the interrupt
                                                          // because we are going to do math and functions that are going to mess up if the values
                                                          // changes in the middle of the cycle.

unsigned int zeroDebouncingExtras[2];  // Stores the extra value added to the ZERO_TIMEOUT to debounce it.
                                       // The ZERO_TIMEOUT needs debouncing so when the value is close to the threshold it
                                       // doesn't jump from 0 to the value. This extra value changes the threshold a little
                                       // when we show a 0.

// Smoothing

unsigned int rpmReadings[2][NUM_RPM_READINGS];  // The inputs.
unsigned int readIndexes[2];                    // The indexes of the current reading.
unsigned int rpmTotals[2];                      // The running total.
unsigned int rpmAverages[2];                    // The rpm values after applying the smoothing.
volatile uint16_t rpmAverage = 0;               // The average rpm value between sensors.

void setup() {
  Serial.begin(9600);

  while (!Serial)
    ;

  Serial.println("Set pin modes");

  pinMode(INTERRUPT_PIN_1, INPUT_PULLUP);
  pinMode(INTERRUPT_PIN_2, INPUT_PULLUP);


  Serial.println("Attach interrupts");

  attachInterrupt(digitalPinToInterrupt(INTERRUPT_PIN_1), handleInterrupt1, RISING);
  attachInterrupt(digitalPinToInterrupt(INTERRUPT_PIN_2), handleInterrupt2, RISING);

  if (IMU.begin()) {
    Serial.print("Accelerometer sample rate = ");
    Serial.print(IMU.accelerationSampleRate());
    Serial.println(" Hz");
  } else {
    Serial.println("Failed to initialize IMU!");
  }

  Serial.println("Initialize I2C");

  Wire.begin(I2C_ADDRESS);
  Wire.setClock(100000UL);
  Wire.onRequest(i2cRequestHandler);

  Serial.println("I2C initialized");

  delay(1000);  // We sometimes take several readings of the period to average. Since we don't have any readings
                // stored we need a high enough value in micros() so if divided is not going to give negative values.
                // The delay allows the micros() to be high enough for the first few cycles.
}

void loop() {
  for (int i = 0; i < 2; i++) {
    // The following is going to store the two values that might change in the middle of the cycle.
    // We are going to do math and functions with those values and they can create glitches if they change in the
    // middle of the cycle.
    lastCycleMeasureTimes[i] = lastMeasureTimes[i];  // Store the lastMeasureTime in a variable.
    currentMicros[i] = micros();                     // Store the micros() in a variable.

    // currentMicros should always be higher than lastMeasureTime, but in rare occasions that's not true.
    // I'm not sure why this happens, but my solution is to compare both and if currentMicros is lower than
    // lastCycleMeasureTime I set it as the currentMicros.
    // The need of fixing this is that we later use this information to see if pulses stopped.
    if (currentMicros[i] < lastCycleMeasureTimes[i]) {
      lastCycleMeasureTimes[i] = currentMicros[i];
    }

    frequencies[i] = 10000000000 / periodAverages[i];  // Calculate the frequency using the period between pulses.

    // Detect if pulses stopped or frequency is too low, so we can show 0 frequency:
    if (periodsBetweenPulses[i] > ZERO_TIMEOUT - zeroDebouncingExtras[i] || currentMicros[i] - lastCycleMeasureTimes[i] > ZERO_TIMEOUT - zeroDebouncingExtras[i]) {  // If the pulses are too far apart that we reached the timeout for zero:
      frequencies[i] = 0;                                                                                                                                            // Set frequency as 0.
      zeroDebouncingExtras[i] = 2000;                                                                                                                                // Change the threshold a little so it doesn't bounce.
    } else {
      zeroDebouncingExtras[i] = 0;  // Reset the threshold to the normal value so it doesn't bounce.
    }

    // Calculate the rpm:
    rpm[i] = frequencies[i] * 60 / PULSES_PER_REVOLUTION / 10000;

    // Smoothing
    rpmTotals[i] = rpmTotals[i] - rpmReadings[i][readIndexes[i]];
    rpmReadings[i][readIndexes[i]] = rpm[i];                       // Takes the value that we are going to smooth.
    rpmTotals[i] = rpmTotals[i] + rpmReadings[i][readIndexes[i]];  // Add the reading to the total.
    readIndexes[i] = readIndexes[i] + 1;                           // Advance to the next position in the array.

    // If we're at the end of the array
    if (readIndexes[i] >= NUM_RPM_READINGS) {
      readIndexes[i] = 0;  // Reset array index.
    }

    rpmAverages[i] = rpmTotals[i] / NUM_RPM_READINGS;
  }

  rpmAverage = (rpmAverages[0] + rpmAverages[1]) / 2;

  if (IMU.accelerationAvailable()) {
    float xRaw, yRaw, zRaw;

    IMU.readAcceleration(xRaw, yRaw, zRaw);

    if (fabs(xOffset) < 0.0001 && fabs(yOffset) < 0.0001) {
      xOffset = xRaw;
      yOffset = yRaw;
      Serial.println("xOffset: " + String(xOffset) + "\tyOffset: " + String(yOffset));
    }

    x = (int8_t)(255 * min((xRaw - xOffset), 1));
    y = (int8_t)(255 * min((yRaw - yOffset), 1));

    // Serial.println("xRaw: " + String(xRaw) + "\tx: " + String(x) + "\tyRaw: " + String(yRaw) + "\ty: " + String(y));
  }

  // Serial.println("rpm[0]: " + String(rpmAverages[0]) + "\trpm[1]: " + String(rpmAverages[1]) + "\trpm: " + String(rpmAverage));
}

void handleInterrupt(int i) {
  periodsBetweenPulses[i] = micros() - lastMeasureTimes[i];
  lastMeasureTimes[i] = micros();

  if (pulseCounters[i] >= amountsOfRpmReadings[i]) {
    periodAverages[i] = periodSums[i] / amountsOfRpmReadings[i];  // Calculate the final period dividing the sum of all readings by the
                                                                  // amount of readings to get the average.
    pulseCounters[i] = 1;                                         // Reset the counter to start over. The reset value is 1 because its the minimum setting allowed (1 reading).
    periodSums[i] = periodsBetweenPulses[i];                      // Reset periodSum to start a new averaging operation.

    // Change the amount of readings depending on the period between pulses.
    // To be very responsive, ideally we should read every pulse. The problem is that at higher speeds the period gets
    // too low decreasing the accuracy. To get more accurate readings at higher speeds we should get multiple pulses and
    // average the period, but if we do that at lower speeds then we would have readings too far apart (laggy or sluggish).
    // To have both advantages at different speeds, we will change the amount of readings depending on the period between pulses.
    int remapedAmountOfReadings = map(periodsBetweenPulses[i], 40000, 5000, 1, 10);  // Remap the period range to the reading range.

    // 1st value is what are we going to remap. In this case is the periodsBetweenPulses[.
    // 2nd value is the period value when we are going to have only 1 reading. The higher it is, the lower rpm has to be to reach 1 reading.
    // 3rd value is the period value when we are going to have 10 readings. The higher it is, the lower rpm has to be to reach 10 readings.
    // 4th and 5th values are the amount of readings range.
    remapedAmountOfReadings = constrain(remapedAmountOfReadings, 1, 10);  // Constrain the value so it doesn't go below or above the limits.

    amountsOfRpmReadings[i] = remapedAmountOfReadings;
  } else {
    pulseCounters[i]++;
    periodSums[i] = periodSums[i] + periodsBetweenPulses[i];
  }
}

void handleInterrupt1() {
  handleInterrupt(0);
}

void handleInterrupt2() {
  handleInterrupt(1);
}

void i2cRequestHandler() {
  bytes[0] = (uint8_t)(rpmAverage & 0xff);
  bytes[1] = (uint8_t)(rpmAverage >> 8);
  bytes[2] = x;
  bytes[3] = y;

  Wire.write(bytes, 4);
}
