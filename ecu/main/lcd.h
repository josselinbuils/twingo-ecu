#ifndef _LCD_H_
#define _LCD_H_

#include "esp_check.h"
#include "esp_err.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_rgb.h"
#include "esp_lcd_touch_gt911.h"
#include "esp_log.h"
#include "esp_lvgl_port.h"
#include "driver/gpio.h"
#include "driver/i2c.h"

// I2C settings
#define I2C_SCL_PIN 9
#define I2C_SDA_PIN 8
#define I2C_NUM 0
#define I2C_FREQ_HZ 400000
#define I2C_TX_BUF_DISABLE 0
#define I2C_RX_BUF_DISABLE 0
#define I2C_TIMEOUT_MS 1000

// GPIO settings
#define GPIO_INPUT_IO_4 4
#define GPIO_INPUT_PIN_SEL 1ULL << GPIO_INPUT_IO_4

// LCD size
#define LCD_H_RES (1024)
#define LCD_V_RES (600)

// LCD settings
#define LCD_LVGL_FULL_REFRESH (0)
#define LCD_LVGL_DIRECT_MODE (1)
#define LCD_LVGL_AVOID_TEAR (1)
#define LCD_RGB_BOUNCE_BUFFER_MODE (1)
#define LCD_DRAW_BUFF_DOUBLE (0)
#define LCD_DRAW_BUFF_HEIGHT (100)
#define LCD_RGB_BUFFER_NUMS (2)
#define LCD_RGB_BOUNCE_BUFFER_HEIGHT (10)

// LCD specifications
#define LCD_PIXEL_CLOCK_HZ (21 * 1000 * 1000)
#define LCD_BIT_PER_PIXEL (16)
#define LCD_DATA_WIDTH (16)

// LCD pins
#define LCD_GPIO_DISP (-1)
#define LCD_GPIO_VSYNC (GPIO_NUM_3)
#define LCD_GPIO_HSYNC (GPIO_NUM_46)
#define LCD_GPIO_DE (GPIO_NUM_5)
#define LCD_GPIO_PCLK (GPIO_NUM_7)
#define LCD_GPIO_DATA0 (GPIO_NUM_14)
#define LCD_GPIO_DATA1 (GPIO_NUM_38)
#define LCD_GPIO_DATA2 (GPIO_NUM_18)
#define LCD_GPIO_DATA3 (GPIO_NUM_17)
#define LCD_GPIO_DATA4 (GPIO_NUM_10)
#define LCD_GPIO_DATA5 (GPIO_NUM_39)
#define LCD_GPIO_DATA6 (GPIO_NUM_0)
#define LCD_GPIO_DATA7 (GPIO_NUM_45)
#define LCD_GPIO_DATA8 (GPIO_NUM_48)
#define LCD_GPIO_DATA9 (GPIO_NUM_47)
#define LCD_GPIO_DATA10 (GPIO_NUM_21)
#define LCD_GPIO_DATA11 (GPIO_NUM_1)
#define LCD_GPIO_DATA12 (GPIO_NUM_2)
#define LCD_GPIO_DATA13 (GPIO_NUM_42)
#define LCD_GPIO_DATA14 (GPIO_NUM_41)
#define LCD_GPIO_DATA15 (GPIO_NUM_40)

void gpio_init();
esp_err_t i2c_init();
esp_err_t lcd_init();
esp_err_t lcd_backlight_on();
esp_err_t lcd_backlight_off();
esp_err_t lvgl_init();
esp_err_t touch_init();

#endif
