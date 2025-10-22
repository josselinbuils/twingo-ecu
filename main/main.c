#include "twingo_logo.c"
#include "waveshare_rgb_lcd_port.h"
#include <driver/i2c.h>
#include <lvgl.h>

#define NUM_RPM_READINGS 2

#define ACK_CHECK_EN 0x1
#define ACK_VAL 0x0
#define NACK_VAL 0x1
#define READ_BIT I2C_MASTER_READ
#define TACHOMETER_I2C_ADDRESS 0x1

const int BORDER_WIDTH = 5;
const int INDICATOR_WIDTH = 40;
const int MAJOR_TICK_WIDTH = 4;
const int MINOR_TICK_WIDTH = 4;
const int PADDING = 14;

lv_obj_t *img;
lv_obj_t *meter;
lv_meter_indicator_t *indic;

int ignitionCounter = 0;
int lastIgnitionStatus0 = 0;
int lastIgnitionStatus1 = 0;
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
      lv_snprintf(dsc->text, sizeof(dsc->text), "%d", (int)dsc->value);
    }
  }
}

bool check_i2c_device(i2c_port_t port, uint8_t address) {
  esp_err_t ret;

  i2c_cmd_handle_t cmd = i2c_cmd_link_create();
  i2c_master_start(cmd);
  i2c_master_write_byte(cmd, (address << 1) | I2C_MASTER_WRITE, true);
  i2c_master_stop(cmd);

  ret = i2c_master_cmd_begin(port, cmd, 50 / portTICK_PERIOD_MS);
  i2c_cmd_link_delete(cmd);

  if (ret == ESP_OK) {
    return true;
  }
  if (ret != ESP_FAIL) {
    printf("Error at address 0x%02X, error: %s\n", address, esp_err_to_name(ret));
  }
  return false;
}

static esp_err_t __attribute__((unused)) i2c_master_read_slave(
  i2c_port_t i2c_num, uint8_t *data_rd, size_t size
) {
  if (size == 0) {
    return ESP_OK;
  }
  i2c_cmd_handle_t cmd = i2c_cmd_link_create();
  i2c_master_start(cmd);
  i2c_master_write_byte(cmd, (TACHOMETER_I2C_ADDRESS << 1) | READ_BIT, ACK_CHECK_EN);
  if (size > 1) {
    i2c_master_read(cmd, data_rd, size - 1, ACK_VAL);
  }
  i2c_master_read_byte(cmd, data_rd + size - 1, NACK_VAL);
  i2c_master_stop(cmd);
  esp_err_t ret = i2c_master_cmd_begin(i2c_num, cmd, 50 / portTICK_PERIOD_MS);
  i2c_cmd_link_delete(cmd);
  return ret;
}

void app_main() {
  lv_color_t BACKGROUND_COLOR = lv_color_hex(0x101200);
  lv_color_t COLOR = lv_color_hex(0xa4b700);

  waveshare_esp32_s3_rgb_lcd_init(); // Initialize the Waveshare ESP32-S3 RGB LCD
  // wavesahre_rgb_lcd_bl_on();  //Turn on the screen backlight
  // wavesahre_rgb_lcd_bl_off(); //Turn off the screen backlight

  if (lvgl_port_lock(-1)) {
    lv_obj_set_style_bg_color(lv_scr_act(), BACKGROUND_COLOR, LV_PART_MAIN);
    lv_obj_clear_flag(lv_scr_act(), LV_OBJ_FLAG_SCROLLABLE);

    meter = lv_meter_create(lv_scr_act());

    lv_obj_clear_flag(meter, LV_OBJ_FLAG_SCROLLABLE);

    // Remove outside circle, padding, and circle from the middle
    lv_obj_remove_style(meter, NULL, LV_PART_MAIN);
    lv_obj_remove_style(meter, NULL, LV_PART_INDICATOR);

    lv_obj_add_event_cb(meter, meterEventCallback, LV_EVENT_DRAW_PART_BEGIN, NULL);
    lv_obj_align(meter, LV_ALIGN_CENTER, 0, 250);
    lv_obj_set_size(meter, 940, 940);
    lv_obj_set_style_bg_color(meter, BACKGROUND_COLOR, LV_PART_MAIN);

    lv_meter_scale_t *scale = lv_meter_add_scale(meter);

    lv_meter_set_scale_range(meter, scale, 0, 6000, 180, 180);
    lv_meter_set_scale_ticks(
      meter, scale, 151, MINOR_TICK_WIDTH, INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2, BACKGROUND_COLOR
    );
    lv_meter_set_scale_major_ticks(
      meter,
      scale,
      25,
      MAJOR_TICK_WIDTH,
      INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2,
      BACKGROUND_COLOR,
      50
    );
    lv_obj_set_style_text_font(meter, &lv_font_montserrat_48, LV_PART_MAIN);
    lv_obj_set_style_text_color(meter, COLOR, 0);
    lv_obj_set_style_pad_all(meter, PADDING + BORDER_WIDTH, LV_PART_MAIN);

    // Add arc indicators
    indic = lv_meter_add_arc(meter, scale, INDICATOR_WIDTH + MINOR_TICK_WIDTH * 2, COLOR, 0);

    lv_meter_indicator_t *indic2 =
      lv_meter_add_arc(meter, scale, BORDER_WIDTH, COLOR, PADDING * 1.2);

    lv_meter_set_indicator_start_value(meter, indic2, 0);
    lv_meter_set_indicator_end_value(meter, indic2, 6000);

    lv_meter_indicator_t *indic3 = lv_meter_add_arc(
      meter, scale, BORDER_WIDTH, COLOR, -INDICATOR_WIDTH - BORDER_WIDTH - PADDING
    );

    lv_meter_set_indicator_start_value(meter, indic3, 0);
    lv_meter_set_indicator_end_value(meter, indic3, 6000);

    static lv_style_t lineStyle;

    lv_style_init(&lineStyle);
    lv_style_set_line_width(&lineStyle, BORDER_WIDTH);
    lv_style_set_line_color(&lineStyle, COLOR);

    const int lineWidth = INDICATOR_WIDTH + PADDING * 2 + BORDER_WIDTH + 2;
    static lv_point_t linePoints[] = {
      {0, 0},
      {1, 2},
      {2, 4},
      {4, 6},
      {6, 8},
      {8, 10},
      {lineWidth - 8, 10},
      {lineWidth - 6, 8},
      {lineWidth - 4, 6},
      {lineWidth - 2, 4},
      {lineWidth - 1, 2},
      {lineWidth, 0}
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

    lvgl_port_unlock();
  }

  while (!check_i2c_device(I2C_NUM_0, TACHOMETER_I2C_ADDRESS)) {
    printf("Wait for tachometer device...\n");
    vTaskDelay(500 / portTICK_PERIOD_MS);
  }
  printf("Tachometer device found!\n");

  while (true) {
    int ret;
    uint8_t *buffer = (uint8_t *)malloc(2);
    ret = i2c_master_read_slave(I2C_NUM_0, (uint8_t *)buffer, 2);

    if (ret == ESP_ERR_TIMEOUT) {
      printf("I2C Timeout\n");
    } else if (ret == ESP_OK) {
      if (lvgl_port_lock(-1)) {
        uint16_t rpm = buffer[0] | (buffer[1] << 8);
        lv_meter_set_indicator_end_value(meter, indic, rpm);
        lvgl_port_unlock();
        // printf("rpm: %d\n", rpm);
      }
    } else {
      printf("Master read slave error, IO not connected...\n");
    }
  }
}
