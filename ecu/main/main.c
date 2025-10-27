#include "ble.h"
#include "esp_lvgl_port.h"
#include "lcd.h"
#include "twingo_logo.c"
#include "external/waveshare_twai_port.h"
#include <driver/i2c.h>
#include <lvgl.h>

#define TAG "ECU"

#define ACK_CHECK_EN 0x1
#define ACK_VAL 0x0
#define NACK_VAL 0x1
#define READ_BIT I2C_MASTER_READ
#define TACHOMETER_I2C_ADDRESS 0x1

const int BORDER_WIDTH = 5;
const int INDICATOR_RANGE = 6000;
const int INDICATOR_WIDTH = 40;
const int LABELS_GAP = 30;
const int PADDING = 10;
const int SCALE_SIZE = 940;
const int TICK_LENGTH = INDICATOR_WIDTH + PADDING * 2 - 2;
const int TICK_WIDTH = 2;

lv_obj_t *indic;
lv_obj_t *label;

bool is_backlight_on = true;

void click_handler(lv_event_t *event) {
  if (is_backlight_on) {
    lcd_backlight_on();
  } else {
    lcd_backlight_off();
  }
  is_backlight_on = !is_backlight_on;
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

void set_label_text(const char *text) {
  ESP_LOGI(TAG, "Set label text: %s", text);

  if (lvgl_port_lock(-1)) {
    lv_label_set_text(label, text);
    lvgl_port_unlock();
  }
}

void app_main() {
  lv_color_t BACKGROUND_COLOR = lv_color_hex(0x101200);
  lv_color_t COLOR = lv_color_hex(0xa4b700);

  // ESP_LOGI(TAG, "Initialize CAN bus");
  // ESP_ERROR_CHECK(waveshare_twai_init());

  ESP_LOGI(TAG, "Initialize LCD panel");
  ESP_ERROR_CHECK(lcd_init());

  ESP_LOGI(TAG, "Initialize I2C");
  ESP_ERROR_CHECK(i2c_init());

  ESP_LOGI(TAG, "Initialize GPIO");
  gpio_init();

  ESP_LOGI(TAG, "Initialize touch");
  ESP_ERROR_CHECK(touch_init());

  ESP_LOGI(TAG, "Initialize LVGL");
  ESP_ERROR_CHECK(lvgl_init());

  vTaskDelay(1); // Prevent LVGL slow boot

  if (lvgl_port_lock(-1)) {
    lv_obj_set_style_bg_color(lv_screen_active(), BACKGROUND_COLOR, LV_PART_MAIN);
    lv_obj_clear_flag(lv_screen_active(), LV_OBJ_FLAG_SCROLLABLE);

    // Arc indicator

    indic = lv_arc_create(lv_screen_active());

    lv_obj_remove_style(indic, NULL, LV_PART_KNOB);
    lv_obj_align(indic, LV_ALIGN_CENTER, 0, 250);
    lv_obj_set_size(indic, SCALE_SIZE, SCALE_SIZE);
    lv_obj_set_style_arc_opa(indic, 0, LV_PART_MAIN);
    lv_obj_set_style_arc_color(indic, COLOR, LV_PART_INDICATOR);
    lv_obj_set_style_arc_width(indic, INDICATOR_WIDTH, LV_PART_MAIN);
    lv_obj_set_style_arc_width(indic, INDICATOR_WIDTH, LV_PART_INDICATOR);
    lv_obj_set_style_radius(indic, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(indic, 0, LV_PART_INDICATOR);
    lv_arc_set_rotation(indic, 180);
    lv_arc_set_bg_angles(indic, 0, 180);
    lv_arc_set_range(indic, 0, INDICATOR_RANGE);
    lv_obj_set_style_pad_all(indic, PADDING + BORDER_WIDTH, LV_PART_MAIN);

    static lv_style_t indic_style;

    lv_style_init(&indic_style);
    lv_style_set_arc_rounded(&indic_style, false);
    lv_obj_add_style(indic, &indic_style, LV_PART_INDICATOR);

    // Scales

    lv_obj_t *inner_scale = lv_scale_create(lv_screen_active());
    lv_obj_t *outer_scale = lv_scale_create(lv_screen_active());

    lv_obj_clear_flag(inner_scale, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_clear_flag(outer_scale, LV_OBJ_FLAG_SCROLLABLE);

    lv_scale_set_mode(inner_scale, LV_SCALE_MODE_ROUND_INNER);
    lv_scale_set_mode(outer_scale, LV_SCALE_MODE_ROUND_OUTER);

    lv_obj_align(inner_scale, LV_ALIGN_CENTER, 0, 250);
    lv_obj_align(outer_scale, LV_ALIGN_CENTER, 0, 250);

    lv_obj_set_size(inner_scale, SCALE_SIZE, SCALE_SIZE);
    lv_obj_set_size(outer_scale, SCALE_SIZE, SCALE_SIZE);

    lv_scale_set_angle_range(inner_scale, 180);
    lv_scale_set_angle_range(outer_scale, 180);

    lv_scale_set_rotation(inner_scale, 180);
    lv_scale_set_rotation(outer_scale, 180);

    static lv_style_t main_line_style;

    lv_style_init(&main_line_style);
    lv_style_set_bg_color(&main_line_style, BACKGROUND_COLOR);
    lv_style_set_arc_color(&main_line_style, COLOR);
    lv_style_set_arc_width(&main_line_style, BORDER_WIDTH);

    lv_obj_add_style(inner_scale, &main_line_style, LV_PART_MAIN);
    lv_obj_add_style(outer_scale, &main_line_style, LV_PART_MAIN);

    // Inner scale

    lv_obj_add_event(inner_scale, click_handler, LV_EVENT_CLICKED, NULL);

    lv_scale_set_range(inner_scale, 0, INDICATOR_RANGE);

    lv_scale_set_total_tick_count(inner_scale, 151);
    lv_scale_set_major_tick_every(inner_scale, 25);

    lv_obj_set_style_length(inner_scale, TICK_LENGTH, LV_PART_ITEMS);
    lv_obj_set_style_length(inner_scale, TICK_LENGTH, LV_PART_INDICATOR);

    static lv_style_t tick_style;

    lv_style_init(&tick_style);
    lv_style_set_line_width(&tick_style, TICK_WIDTH);
    lv_style_set_line_color(&tick_style, BACKGROUND_COLOR);
    lv_style_set_line_width(&tick_style, TICK_WIDTH);
    lv_style_set_text_color(&tick_style, COLOR);
    lv_style_set_text_font(&tick_style, &lv_font_montserrat_48);

    lv_scale_section_t *main_section = lv_scale_add_section(inner_scale);

    lv_scale_section_set_range(main_section, 0, INDICATOR_RANGE);
    lv_scale_set_section_style_items(inner_scale, main_section, &tick_style);
    lv_scale_set_section_style_indicator(inner_scale, main_section, &tick_style);

    static char *labels[7] = {"0", "1", "2", "3", "4", "5", "6", NULL};

    lv_scale_set_label_show(inner_scale, true);
    lv_scale_set_text_src(inner_scale, labels);
    lv_obj_set_style_pad_radial(inner_scale, LABELS_GAP, LV_PART_INDICATOR);

    // Outer scale

    lv_scale_set_label_show(outer_scale, false);
    lv_scale_set_total_tick_count(outer_scale, 2);
    lv_obj_set_style_length(outer_scale, 0, LV_PART_ITEMS);
    lv_obj_set_style_pad_all(
      outer_scale, BORDER_WIDTH + PADDING + INDICATOR_WIDTH + PADDING, LV_PART_MAIN
    );

    // End lines

    static lv_style_t line_style;

    lv_style_init(&line_style);
    lv_style_set_line_width(&line_style, BORDER_WIDTH);
    lv_style_set_line_color(&line_style, COLOR);

    const int end_line_width = INDICATOR_WIDTH + PADDING * 2 + BORDER_WIDTH * 2 - 2;

    static lv_point_precise_t end_line_points[] = {
      {0, 0},
      {1, 2},
      {2, 4},
      {4, 6},
      {6, 8},
      {8, 10},
      {end_line_width - 8, 10},
      {end_line_width - 6, 8},
      {end_line_width - 4, 6},
      {end_line_width - 2, 4},
      {end_line_width - 1, 2},
      {end_line_width, 0}
    };

    lv_obj_t *end_line_1 = lv_line_create(inner_scale);

    lv_line_set_points(end_line_1, end_line_points, 12);
    lv_obj_add_style(end_line_1, &line_style, 0);
    lv_obj_align(end_line_1, LV_ALIGN_LEFT_MID, 0, 3);

    lv_obj_t *end_line_2 = lv_line_create(inner_scale);

    lv_line_set_points(end_line_2, end_line_points, 12);
    lv_obj_add_style(end_line_2, &line_style, 0);
    lv_obj_align(end_line_2, LV_ALIGN_RIGHT_MID, 0, 3);

    // Add twingo logo

    LV_IMG_DECLARE(twingo_logo)

    lv_obj_t *img = lv_img_create(inner_scale);

    lv_img_set_src(img, &twingo_logo);
    lv_obj_set_style_img_recolor_opa(img, LV_OPA_100, 0);
    lv_obj_set_style_img_recolor(img, COLOR, 0);
    lv_obj_align(img, LV_ALIGN_CENTER, 0, -150);

    // Add text

    label = lv_label_create(inner_scale);
    lv_obj_set_height(label, 30);
    lv_obj_set_width(label, 500);
    lv_label_set_long_mode(label, LV_LABEL_LONG_MODE_DOTS);
    lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, 0);

    static lv_style_t label_style;

    lv_style_init(&label_style);
    lv_style_set_text_color(&label_style, COLOR);
    lv_style_set_text_font(&label_style, &lv_font_montserrat_28);
    lv_obj_add_style(label, &label_style, 0);

    // lv_arc_set_value(indic, 5500);

    lvgl_port_unlock();
  }

  ESP_LOGI(TAG, "Initialize BLE");
  init_ble(set_label_text);

  while (!check_i2c_device(I2C_NUM_0, TACHOMETER_I2C_ADDRESS)) {
    ESP_LOGI(TAG, "Wait for tachometer device...");
    vTaskDelay(500 / portTICK_PERIOD_MS);
  }
  ESP_LOGI(TAG, "Tachometer device found!");

  while (true) {
    int ret;
    uint8_t *buffer = (uint8_t *)malloc(2);
    ret = i2c_master_read_slave(I2C_NUM_0, (uint8_t *)buffer, 2);

    if (ret == ESP_ERR_TIMEOUT) {
      ESP_LOGW(TAG, "I2C Timeout\n");
    } else if (ret == ESP_OK) {
      if (lvgl_port_lock(-1)) {
        uint16_t rpm = buffer[0] | (buffer[1] << 8);
        lv_arc_set_value(indic, rpm);
        lvgl_port_unlock();
        // ESP_LOGI(TAG, "rpm: %d\n", rpm);
      }
      free(buffer);
    } else {
      ESP_LOGI(TAG, "Master read slave error, IO not connected...\n");
    }

    // ESP_ERROR_CHECK(waveshare_twai_receive());
  }
}
