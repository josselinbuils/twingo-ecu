#include "lcd.h"

#define TAG "LCD"

esp_lcd_panel_handle_t lcd_panel = NULL;
esp_lcd_touch_handle_t touch_handle = NULL;

esp_err_t touch_init(void);
void touch_reset(void);

void gpio_init(void) {
  // Zero-initialize the config structure
  gpio_config_t io_conf = {};

  // Disable interrupt
  io_conf.intr_type = GPIO_INTR_DISABLE;

  // Bit mask of the pins, use GPIO4 here
  io_conf.pin_bit_mask = GPIO_INPUT_PIN_SEL;

  // Set as input mode
  io_conf.mode = GPIO_MODE_OUTPUT;

  gpio_config(&io_conf);
}

esp_err_t lcd_backlight_off() {
  // Configure CH422G to output mode
  uint8_t write_buf = 0x01;
  i2c_master_write_to_device(I2C_NUM, 0x24, &write_buf, 1, I2C_TIMEOUT_MS / portTICK_PERIOD_MS);

  // Turn off the screen backlight by pulling the backlight pin low
  write_buf = 0x1A;
  i2c_master_write_to_device(I2C_NUM, 0x38, &write_buf, 1, I2C_TIMEOUT_MS / portTICK_PERIOD_MS);
  return ESP_OK;
}

esp_err_t lcd_backlight_on() {
  // Configure CH422G to output mode
  uint8_t write_buf = 0x01;
  i2c_master_write_to_device(I2C_NUM, 0x24, &write_buf, 1, I2C_TIMEOUT_MS / portTICK_PERIOD_MS);

  // Pull the backlight pin high to light the screen backlight
  write_buf = 0x1E;
  i2c_master_write_to_device(I2C_NUM, 0x38, &write_buf, 1, I2C_TIMEOUT_MS / portTICK_PERIOD_MS);
  return ESP_OK;
}

esp_err_t lcd_init(void) {
  esp_err_t ret = ESP_OK;

  esp_lcd_rgb_panel_config_t panel_conf = {
    .bits_per_pixel = LCD_BIT_PER_PIXEL,
#if LCD_RGB_BOUNCE_BUFFER_MODE
    .bounce_buffer_size_px = LCD_H_RES * LCD_RGB_BOUNCE_BUFFER_HEIGHT,
#endif
    .clk_src = LCD_CLK_SRC_DEFAULT,
    .data_gpio_nums =
      {
        LCD_GPIO_DATA0,
        LCD_GPIO_DATA1,
        LCD_GPIO_DATA2,
        LCD_GPIO_DATA3,
        LCD_GPIO_DATA4,
        LCD_GPIO_DATA5,
        LCD_GPIO_DATA6,
        LCD_GPIO_DATA7,
        LCD_GPIO_DATA8,
        LCD_GPIO_DATA9,
        LCD_GPIO_DATA10,
        LCD_GPIO_DATA11,
        LCD_GPIO_DATA12,
        LCD_GPIO_DATA13,
        LCD_GPIO_DATA14,
        LCD_GPIO_DATA15,
      },
    .data_width = LCD_DATA_WIDTH,
    .de_gpio_num = LCD_GPIO_DE,
    .disp_gpio_num = LCD_GPIO_DISP,
    .flags =
      {
        .fb_in_psram = 1, // Use PSRAM for framebuffer
      },
    .hsync_gpio_num = LCD_GPIO_HSYNC,
    .num_fbs = LCD_RGB_BUFFER_NUMS,
    .pclk_gpio_num = LCD_GPIO_PCLK,
    .psram_trans_align = 64,
    .sram_trans_align = 4,
    .timings =
      {
        .pclk_hz = LCD_PIXEL_CLOCK_HZ, // Pixel clock frequency
        .h_res = LCD_H_RES, // Horizontal resolution
        .v_res = LCD_V_RES, // Vertical resolution
        .hsync_back_porch = 145, // Horizontal sync pulse width
        .hsync_front_porch = 170, // Horizontal back porch
        .hsync_pulse_width = 30, // Horizontal front porch
        .vsync_back_porch = 23, // Vertical sync pulse width
        .vsync_front_porch = 12, // Vertical back porch
        .vsync_pulse_width = 2, // Vertical front porch
        .flags =
          {
            .pclk_active_neg = 1, // Active low pixel clock
          },
      },
    .vsync_gpio_num = LCD_GPIO_VSYNC,
  };
  ESP_GOTO_ON_ERROR(esp_lcd_new_rgb_panel(&panel_conf, &lcd_panel), err, TAG, "RGB init failed");
  ESP_GOTO_ON_ERROR(esp_lcd_panel_init(lcd_panel), err, TAG, "LCD init failed");

  return touch_init();

err:
  if (lcd_panel) {
    esp_lcd_panel_del(lcd_panel);
  }
  return ret;
}

// To debug, add ESP_LOGD(TAG, "Touch position: %u,%u", touchpad_x[0], touchpad_y[0]); to
// esp_lvgl_port_touch/c -> lvgl_port_touchpad_read (line 134).
esp_err_t touch_init(void) {
  touch_reset();

  esp_lcd_panel_io_handle_t tp_io_handle = NULL;
  const esp_lcd_panel_io_i2c_config_t tp_io_config = ESP_LCD_TOUCH_IO_I2C_GT911_CONFIG();

  ESP_ERROR_CHECK(
    esp_lcd_new_panel_io_i2c((esp_lcd_i2c_bus_handle_t)I2C_NUM, &tp_io_config, &tp_io_handle)
  );

  const esp_lcd_touch_config_t tp_cfg = {
    .x_max = LCD_H_RES,
    .y_max = LCD_V_RES,
    .rst_gpio_num = GPIO_NUM_NC,
    .int_gpio_num = GPIO_NUM_NC,
    .levels =
      {
        .reset = 0,
        .interrupt = 0,
      },
    .flags = {
      .swap_xy = 0,
      .mirror_x = 0,
      .mirror_y = 0,
    },
  };

  return esp_lcd_touch_new_i2c_gt911(tp_io_handle, &tp_cfg, &touch_handle);
}

void touch_reset(void) {
  uint8_t write_buf = 0x01;
  i2c_master_write_to_device(I2C_NUM, 0x24, &write_buf, 1, I2C_TIMEOUT_MS / portTICK_PERIOD_MS);

  // Reset the touch screen. It is recommended to reset the touch screen before using it.
  write_buf = 0x2C;
  i2c_master_write_to_device(I2C_NUM, 0x38, &write_buf, 1, I2C_TIMEOUT_MS / portTICK_PERIOD_MS);
  esp_rom_delay_us(100 * 1000);
  gpio_set_level(GPIO_INPUT_IO_4, 0);
  esp_rom_delay_us(100 * 1000);
  write_buf = 0x2E;
  i2c_master_write_to_device(I2C_NUM, 0x38, &write_buf, 1, I2C_TIMEOUT_MS / portTICK_PERIOD_MS);
  esp_rom_delay_us(200 * 1000);
}
