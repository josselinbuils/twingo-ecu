#include <Arduino.h>
#include <esp_display_panel.hpp>

#include <lvgl.h>
#include "lvgl_v8_port.h"

using namespace esp_panel::drivers;
using namespace esp_panel::board;

static lv_obj_t *meter;

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

  lv_obj_set_style_bg_color(lv_scr_act(), lv_color_black(), LV_PART_MAIN);
  lv_obj_clear_flag(lv_scr_act(), LV_OBJ_FLAG_SCROLLABLE);

  meter = lv_meter_create(lv_scr_act());
  lv_obj_align(meter, LV_ALIGN_CENTER, 0, 210);
  lv_obj_set_size(meter, 940, 940);
  lv_obj_set_style_bg_color(meter, lv_color_black(), LV_PART_MAIN);

  // Add a scale first
  lv_meter_scale_t *scale = lv_meter_add_scale(meter);

  lv_meter_set_scale_range(meter, scale, 0, 700, 190, 175);
  lv_meter_set_scale_ticks(meter, scale, 36, 4, 40, lv_palette_main(LV_PALETTE_GREY));
  lv_meter_set_scale_major_ticks(meter, scale, 5, 14, 40, lv_color_white(), 70);
  lv_obj_set_style_text_font(meter, &lv_font_montserrat_40, LV_PART_MAIN);

  lv_meter_indicator_t *indic;

  // Add a red arc to the end
  indic = lv_meter_add_arc(meter, scale, 40, lv_palette_main(LV_PALETTE_RED), 0);
  lv_meter_set_indicator_start_value(meter, indic, 600);
  lv_meter_set_indicator_end_value(meter, indic, 700);

  // Make the tick lines red at the end of the scale
  indic = lv_meter_add_scale_lines(meter, scale, lv_palette_main(LV_PALETTE_RED), lv_palette_main(LV_PALETTE_RED), false, 0);
  lv_meter_set_indicator_start_value(meter, indic, 600);
  lv_meter_set_indicator_end_value(meter, indic, 700);

  // Add a needle line indicator
  indic = lv_meter_add_needle_line(meter, scale, 14, lv_palette_main(LV_PALETTE_RED), -60);

  lv_meter_set_indicator_value(meter, indic, 150);

  // Release the mutex
  lvgl_port_unlock();
}

void loop() {
  Serial.println("IDLE loop");
  delay(1000);
}
