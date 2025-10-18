#include <Arduino.h>
#include <esp_display_panel.hpp>
#include <esp_io_expander.hpp>
#include <lvgl.h>

#include "lvgl_v8_port.h"
#include "twingo_logo.c"
//#include "Wire.h"

using namespace esp_panel::drivers;
using namespace esp_panel::board;

const int DI0_PIN = 0;
const int DI1_PIN = 5;
const int SDA_PIN = 8;
const int SCL_PIN = 9;

const int TACHOMETER_I2C_ADDRESS = 1;

const lv_color_t BACKGROUND_COLOR = lv_color_hex(0x101200);
const lv_color_t COLOR = lv_color_hex(0xa4b700);

const int BORDER_WIDTH = 5;
const int INDICATOR_WIDTH = 40;
const int MAJOR_TICK_WIDTH = 4;
const int MINOR_TICK_WIDTH = 4;
const int PADDING = 14;

const byte NUM_RPM_READINGS = 2;

esp_expander::CH422G *expander = NULL;
lv_obj_t *img;
lv_obj_t *meter;
lv_meter_indicator_t *indic;

int ignitionCounter = 0;
int lastIgnitionStatus0 = LOW;
int lastIgnitionStatus1 = LOW;
long lastTimeMs = 0;

unsigned int rpmReadings[NUM_RPM_READINGS];
unsigned int readIndex;
unsigned int rpmTotal;
unsigned int rpmAverage;

void meterEventCallback(lv_event_t *e) {
  lv_event_code_t code = lv_event_get_code(e);

  if (code == LV_EVENT_DRAW_PART_BEGIN) {
    lv_obj_draw_part_dsc_t *dsc = (lv_obj_draw_part_dsc_t *)lv_event_get_param(e);

    if (dsc->value % 1000 == 0) {
      dsc->value /= 1000;
      lv_snprintf(dsc->text, sizeof(dsc->text), "%d", dsc->value);
    }
  }
}

void setup() {
  String title = "LVGL porting example";

  Serial.begin(115200);

  Serial.println("Initializing board");
  Board *board = new Board();
  board->init();

#if LVGL_PORT_AVOID_TEARING_MODE
  auto lcd = board->getLCD();
  // When avoid tearing function is enabled, the frame buffer number should be set in the board driver
  lcd->configFrameBufferNumber(LVGL_PORT_DISP_BUFFER_NUM);
#if ESP_PANEL_DRIVERS_BUS_ENABLE_RGB && CONFIG_IDF_TARGET_ESP32S3
  auto lcd_bus = lcd->getBus();
  /**
     * As the anti-tearing feature typically consumes more PSRAM bandwidth, for the ESP32-S3, we need to utilize the
     * "bounce buffer" functionality to enhance the RGB data bandwidth.
     * This feature will consume `bounce_buffer_size * bytes_per_pixel * 2` of SRAM memory.
     */
  if (lcd_bus->getBasicAttributes().type == ESP_PANEL_BUS_TYPE_RGB) {
    static_cast<BusRGB *>(lcd_bus)->configRGB_BounceBufferSize(lcd->getFrameWidth() * 10);
  }
#endif
#endif
  assert(board->begin());

  Serial.println("Initializing LVGL");
  lvgl_port_init(board->getLCD(), board->getTouch());

  Serial.println("Creating UI");

  // Lock the mutex due to the LVGL APIs are not thread-safe
  lvgl_port_lock(-1);

  lv_obj_set_style_bg_color(lv_scr_act(), BACKGROUND_COLOR, LV_PART_MAIN);
  lv_obj_clear_flag(lv_scr_act(), LV_OBJ_FLAG_SCROLLABLE);

  meter = lv_meter_create(lv_scr_act());

  // Remove outside circle, padding, and circle from the middle
  lv_obj_remove_style(meter, NULL, LV_PART_MAIN);
  lv_obj_remove_style(meter, NULL, LV_PART_INDICATOR);

  lv_obj_add_event_cb(meter, meterEventCallback, LV_EVENT_DRAW_PART_BEGIN, NULL);
  lv_obj_align(meter, LV_ALIGN_CENTER, 0, 250);
  lv_obj_set_size(meter, 940, 940);
  lv_obj_set_style_bg_color(meter, BACKGROUND_COLOR, LV_PART_MAIN);

  lv_meter_scale_t *scale = lv_meter_add_scale(meter);

  lv_meter_set_scale_range(meter, scale, 0, 6000, 180, 180);
  lv_meter_set_scale_ticks(meter, scale, 151, MINOR_TICK_WIDTH, INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2, BACKGROUND_COLOR);
  lv_meter_set_scale_major_ticks(meter, scale, 25, MAJOR_TICK_WIDTH, INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2, BACKGROUND_COLOR, 50);
  lv_obj_set_style_text_font(meter, &lv_font_montserrat_48, LV_PART_MAIN);
  lv_obj_set_style_text_color(meter, COLOR, 0);
  lv_obj_set_style_pad_all(meter, PADDING + BORDER_WIDTH, LV_PART_MAIN);

  // Add arc indicators
  indic = lv_meter_add_arc(meter, scale, INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2, COLOR, 0);

  lv_meter_indicator_t *indic2 = lv_meter_add_arc(meter, scale, BORDER_WIDTH, COLOR, PADDING * 1.2);

  lv_meter_set_indicator_start_value(meter, indic2, 0);
  lv_meter_set_indicator_end_value(meter, indic2, 6000);

  lv_meter_indicator_t *indic3 = lv_meter_add_arc(meter, scale, BORDER_WIDTH, COLOR, -INDICATOR_WIDTH - BORDER_WIDTH - PADDING);

  lv_meter_set_indicator_start_value(meter, indic3, 0);
  lv_meter_set_indicator_end_value(meter, indic3, 6000);

  static lv_style_t lineStyle;

  lv_style_init(&lineStyle);
  lv_style_set_line_width(&lineStyle, BORDER_WIDTH);
  lv_style_set_line_color(&lineStyle, COLOR);

  int lineWidth = INDICATOR_WIDTH + PADDING * 2 + BORDER_WIDTH + 2;
  static lv_point_t linePoints[] = {
    { 0, 0 },
    { 1, 2 },
    { 2, 4 },
    { 4, 6 },
    { 6, 8 },
    { 8, 10 },
    { lineWidth - 8, 10 },
    { lineWidth - 6, 8 },
    { lineWidth - 4, 6 },
    { lineWidth - 2, 4 },
    { lineWidth - 1, 2 },
    { lineWidth, 0 }
  };

  lv_obj_t *line1 = lv_line_create(meter);
  lv_line_set_points(line1, linePoints, 12);
  lv_obj_add_style(line1, &lineStyle, 0);
  lv_obj_align(line1, LV_ALIGN_LEFT_MID, -PADDING, 5);

  lv_obj_t *line2 = lv_line_create(meter);
  lv_line_set_points(line2, linePoints, 12);
  lv_obj_add_style(line2, &lineStyle, 0);
  lv_obj_align(line2, LV_ALIGN_RIGHT_MID, PADDING + BORDER_WIDTH, 5);

  // Add twingo logo
  LV_IMG_DECLARE(twingoLogo)
  img = lv_img_create(meter);
  lv_img_set_src(img, &twingoLogo);
  lv_obj_set_style_img_recolor_opa(img, LV_OPA_100, 0);
  lv_obj_set_style_img_recolor(img, COLOR, 0);
  lv_obj_align(img, LV_ALIGN_CENTER, 0, -150);

  // lv_meter_set_indicator_end_value(meter, indic, 800);

  // Release the mutex
  lvgl_port_unlock();

  Serial.println("Initialize IO expander");

  expander = static_cast<esp_expander::CH422G *>(board->getIO_Expander()->getBase());
  expander->enableAllIO_Input();
  expander->pinMode(DI0_PIN, INPUT);
  expander->pinMode(DI1_PIN, INPUT);

  Serial.println("Initialize I2C");

  //Wire.begin(SDA, SCL);
}

void loop() {
  int ignition_status_0 = expander->digitalRead(DI0_PIN);
  int ignition_status_1 = expander->digitalRead(DI1_PIN);

  if (ignition_status_0 != lastIgnitionStatus0) {
    ignitionCounter++;
    lastIgnitionStatus0 = ignition_status_0;
  }

  if (ignition_status_1 != lastIgnitionStatus1) {
    ignitionCounter++;
    lastIgnitionStatus1 = ignition_status_1;
  }

  int time_ms = millis() - lastTimeMs;

  if (time_ms >= 200) {
    lastTimeMs = millis();

    int rpm = 0;

    if (ignitionCounter > 0) {
      rpm = ignitionCounter * 60000 / time_ms / 8;
    }
    ignitionCounter = 0;

    // Smoothing
    rpmTotal = rpmTotal - rpmReadings[readIndex];
    rpmReadings[readIndex] = rpm;
    rpmTotal = rpmTotal + rpmReadings[readIndex];
    readIndex++;

    if (readIndex >= NUM_RPM_READINGS) {
      readIndex = 0;
    }

    rpmAverage = rpmTotal / NUM_RPM_READINGS;

    lvgl_port_lock(-1);
    lv_meter_set_indicator_end_value(meter, indic, rpmAverage);
    lvgl_port_unlock();

    Serial.print("\nRPM: " + String(rpmAverage));

    // uint8_t bytesReceived = Wire.requestFrom(TACHOMETER_I2C_ADDRESS, 2);

    // Serial.printf("requestFrom: %u\n", bytesReceived);

    // if ((bool)bytesReceived) {
    //   uint8_t buffer[bytesReceived];

    //   Wire.readBytes(buffer, bytesReceived);

    //   uint16_t average = buffer[1] | (buffer[0] << 8);

    //   lvgl_port_lock(-1);
    //   lv_meter_set_indicator_end_value(meter, indic, average);
    //   lvgl_port_unlock();

    //   Serial.print("\nRPM: " + String(average));
    // }
  }
}
