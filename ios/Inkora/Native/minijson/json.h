/*
 * Inkora: a very small JSON reader with the few json-c calls libmypaint uses
 * (reading brush files). Not the json-c library: just enough of its interface.
 */
#ifndef INKORA_MINIJSON_H
#define INKORA_MINIJSON_H
#include <stdint.h>

#define JSON_C_MAJOR_VERSION 0
#define JSON_C_MINOR_VERSION 17

typedef enum json_type {
    json_type_null, json_type_boolean, json_type_double, json_type_int,
    json_type_object, json_type_array, json_type_string
} json_type;

typedef struct json_object json_object;
struct json_object {
    json_type type;
    double num;
    char *str;
    /* object: keys[i] -> vals[i]; array: vals[i] */
    int n, cap;
    char **keys;
    json_object **vals;
};

json_object *json_object_new_object(void);
int json_object_put(json_object *obj);               /* frees it */
json_object *json_tokener_parse(const char *str);
int json_object_is_type(const json_object *obj, json_type type);
json_type json_object_get_type(const json_object *obj);
double json_object_get_double(const json_object *obj);
int32_t json_object_get_int(const json_object *obj);
json_object *json_object_object_get(const json_object *obj, const char *key);
int json_object_object_get_ex(const json_object *obj, const char *key, json_object **value);
size_t json_object_array_length(const json_object *obj);
json_object *json_object_array_get_idx(const json_object *obj, size_t idx);

#define json_object_object_foreach(obj, key, val)                                       \
    char *key = NULL;                                                                  \
    json_object *val = NULL;                                                           \
    for (int key##_i = 0;                                                              \
         (obj) && key##_i < (obj)->n &&                                                \
         ((key = (obj)->keys[key##_i]), (val = (obj)->vals[key##_i]), 1);              \
         key##_i++)

#endif
