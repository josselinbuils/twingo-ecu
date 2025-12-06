#ifndef BLE_H
#define BLE_H

#include "nvs_flash.h"

struct ble_hs_adv_fields;
struct ble_gap_conn_desc;
struct ble_hs_cfg;
union ble_store_value;
union ble_store_key;

int ble_check_connection();

void ble_init(
    void (*bluetooth_state_callback)(bool enabled),
    void (*current_music_callback)(char *current_music),
    void (*music_cover_callback)(uint8_t *music_cover_map),
    void (*speed_limit_callback)(const char *speed_limit)
);

#endif
