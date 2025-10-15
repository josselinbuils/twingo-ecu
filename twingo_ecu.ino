#include <Arduino.h>
#include <esp_display_panel.hpp>
#include <esp_io_expander.hpp>
#include <lvgl.h>

#include "lvgl_v8_port.h"
#include "twingo_logo.c"

using namespace esp_panel::drivers;
using namespace esp_panel::board;

#define DI0 0
#define DI1 5

const lv_color_t BACKGROUND_COLOR = lv_color_black();
const lv_color_t PRIMARY_COLOR = lv_color_hex(0x932348);
const lv_color_t SECONDARY_COLOR = lv_palette_main(LV_PALETTE_GREY);

const int INDICATOR_WIDTH = 50;
const int MAJOR_TICK_WIDTH = 14;
const int MINOR_TICK_WIDTH = 4;


esp_expander::CH422G *expander = NULL;
lv_obj_t *img;
lv_obj_t *meter;
lv_meter_indicator_t *indic;

int ignition_counter = 0;
int last_ignition_status_0 = LOW;
int last_ignition_status_1 = LOW;
long last_time_ms = 0;

void meter_event_callback(lv_event_t *e) {
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

  // Remove outside circle and padding
  lv_obj_remove_style(meter, NULL, LV_PART_MAIN);

  // Remove the circle from the middle
  lv_obj_remove_style(meter, NULL, LV_PART_INDICATOR);

  lv_obj_add_event_cb(meter, meter_event_callback, LV_EVENT_DRAW_PART_BEGIN, NULL);
  lv_obj_align(meter, LV_ALIGN_CENTER, 0, 210);
  lv_obj_set_size(meter, 940, 940);
  lv_obj_set_style_bg_color(meter, BACKGROUND_COLOR, LV_PART_MAIN);

  lv_meter_scale_t *scale = lv_meter_add_scale(meter);

  lv_meter_set_scale_range(meter, scale, 0, 6000, 190, 175);
  lv_meter_set_scale_ticks(meter, scale, 31, MINOR_TICK_WIDTH, INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2, SECONDARY_COLOR);
  lv_meter_set_scale_major_ticks(meter, scale, 5, MAJOR_TICK_WIDTH, INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2, SECONDARY_COLOR, 40);
  lv_obj_set_style_text_font(meter, &lv_font_montserrat_48, LV_PART_MAIN);
  lv_obj_set_style_text_color(meter, SECONDARY_COLOR, 0);

  // Add arc indicators
  indic = lv_meter_add_arc(meter, scale, INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2, PRIMARY_COLOR, 0);

  lv_meter_indicator_t *indic2 = lv_meter_add_arc(meter, scale, MINOR_TICK_WIDTH, SECONDARY_COLOR, 0);

  lv_meter_set_indicator_start_value(meter, indic2, 0);
  lv_meter_set_indicator_end_value(meter, indic2, 6000);

  lv_meter_indicator_t *indic3 = lv_meter_add_arc(meter, scale, MINOR_TICK_WIDTH, SECONDARY_COLOR, -INDICATOR_WIDTH - MINOR_TICK_WIDTH);

  lv_meter_set_indicator_start_value(meter, indic3, 0);
  lv_meter_set_indicator_end_value(meter, indic3, 6000);

  // Add twingo logo
  LV_IMG_DECLARE(twingo_logo)
  img = lv_img_create(meter);
  lv_img_set_src(img, &twingo_logo);
  lv_obj_set_style_img_recolor_opa(img, LV_OPA_100, 0);
  lv_obj_set_style_img_recolor(img, SECONDARY_COLOR, 0);
  lv_obj_align(img, LV_ALIGN_CENTER, 0, -150);

  // Release the mutex
  lvgl_port_unlock();

  Serial.println("Initialize IO expander");

  expander = static_cast<esp_expander::CH422G *>(board->getIO_Expander()->getBase());
  expander->enableAllIO_Input();
  expander->pinMode(DI0, INPUT);
  expander->pinMode(DI1, INPUT);
}

void loop() {
  int ignition_status_0 = expander->digitalRead(DI0);
  int ignition_status_1 = expander->digitalRead(DI1);

  if (ignition_status_0 != last_ignition_status_0) {
    ignition_counter++;
    last_ignition_status_0 = ignition_status_0;
  }

  if (ignition_status_1 != last_ignition_status_1) {
    ignition_counter++;
    last_ignition_status_1 = ignition_status_1;
  }

  int time_ms = millis() - last_time_ms;

  if (time_ms >= 300) {
    last_time_ms = millis();

    int rpm = 0;

    if (ignition_counter > 0) {
      rpm = ignition_counter * 60000 / time_ms / 8;
    }
    ignition_counter = 0;

    lv_meter_set_indicator_end_value(meter, indic, rpm);
    Serial.print("\nRPM: " + String(rpm));
  }
}
