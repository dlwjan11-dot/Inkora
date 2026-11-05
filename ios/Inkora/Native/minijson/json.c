/* Inkora: a very small JSON reader (see json.h). */
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include "json.h"

static json_object *mk(json_type t) {
    json_object *o = (json_object *)calloc(1, sizeof(json_object));
    if (o) o->type = t;
    return o;
}

json_object *json_object_new_object(void) { return mk(json_type_object); }

int json_object_put(json_object *o) {
    if (!o) return 0;
    for (int i = 0; i < o->n; i++) {
        if (o->keys) free(o->keys[i]);
        json_object_put(o->vals[i]);
    }
    free(o->keys); free(o->vals); free(o->str); free(o);
    return 1;
}

static int add(json_object *o, char *key, json_object *v) {
    if (o->n == o->cap) {
        int c = o->cap ? o->cap * 2 : 8;
        json_object **nv = (json_object **)realloc(o->vals, sizeof(json_object *) * c);
        if (!nv) return 0;
        o->vals = nv;
        if (o->type == json_type_object) {
            char **nk = (char **)realloc(o->keys, sizeof(char *) * c);
            if (!nk) return 0;
            o->keys = nk;
        }
        o->cap = c;
    }
    if (o->type == json_type_object) o->keys[o->n] = key;
    o->vals[o->n++] = v;
    return 1;
}

typedef struct { const char *p; int depth; } P;

static void ws(P *s) { while (*s->p && isspace((unsigned char)*s->p)) s->p++; }

static json_object *value(P *s);

static char *string(P *s) {
    if (*s->p != '"') return NULL;
    s->p++;
    size_t cap = 32, n = 0;
    char *b = (char *)malloc(cap);
    if (!b) return NULL;
    while (*s->p && *s->p != '"') {
        char c = *s->p++;
        if (c == '\\') {
            char e = *s->p++;
            switch (e) {
                case 'n': c = '\n'; break; case 't': c = '\t'; break; case 'r': c = '\r'; break;
                case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                case 'u': /* keep it simple: \uXXXX becomes '?' (brush names only) */
                    for (int k = 0; k < 4 && *s->p; k++) s->p++;
                    c = '?'; break;
                case 0: free(b); return NULL;
                default: c = e;
            }
        }
        if (n + 2 > cap) { cap *= 2; char *nb = (char *)realloc(b, cap); if (!nb) { free(b); return NULL; } b = nb; }
        b[n++] = c;
    }
    if (*s->p != '"') { free(b); return NULL; }
    s->p++;
    b[n] = 0;
    return b;
}

static json_object *object(P *s) {
    json_object *o = mk(json_type_object);
    if (!o) return NULL;
    s->p++; ws(s);
    if (*s->p == '}') { s->p++; return o; }
    for (;;) {
        ws(s);
        char *k = string(s);
        if (!k) goto fail;
        ws(s);
        if (*s->p != ':') { free(k); goto fail; }
        s->p++;
        json_object *v = value(s);
        if (!v && s->p == NULL) { free(k); goto fail; }
        if (!add(o, k, v)) { free(k); json_object_put(v); goto fail; }
        ws(s);
        if (*s->p == ',') { s->p++; continue; }
        if (*s->p == '}') { s->p++; return o; }
        goto fail;
    }
fail:
    json_object_put(o);
    s->p = NULL;
    return NULL;
}

static json_object *array(P *s) {
    json_object *o = mk(json_type_array);
    if (!o) return NULL;
    s->p++; ws(s);
    if (*s->p == ']') { s->p++; return o; }
    for (;;) {
        json_object *v = value(s);
        if (s->p == NULL) goto fail;
        if (!add(o, NULL, v)) { json_object_put(v); goto fail; }
        ws(s);
        if (*s->p == ',') { s->p++; continue; }
        if (*s->p == ']') { s->p++; return o; }
        goto fail;
    }
fail:
    json_object_put(o);
    s->p = NULL;
    return NULL;
}

/* returns NULL for JSON null too; a parse error sets s->p to NULL */
static json_object *value(P *s) {
    ws(s);
    if (++s->depth > 64) { s->p = NULL; return NULL; }
    json_object *r = NULL;
    char c = *s->p;
    if (c == '{') r = object(s);
    else if (c == '[') r = array(s);
    else if (c == '"') {
        char *str = string(s);
        if (!str) s->p = NULL;
        else { r = mk(json_type_string); if (r) r->str = str; else free(str); }
    } else if (c == '-' || isdigit((unsigned char)c)) {
        char *end = NULL;
        double d = strtod(s->p, &end);
        if (end == s->p) s->p = NULL;
        else {
            int isint = 1;
            for (const char *q = s->p; q < end; q++) if (*q == '.' || *q == 'e' || *q == 'E') isint = 0;
            r = mk(isint ? json_type_int : json_type_double);
            if (r) r->num = d;
            s->p = end;
        }
    } else if (!strncmp(s->p, "true", 4)) { r = mk(json_type_boolean); if (r) r->num = 1; s->p += 4; }
    else if (!strncmp(s->p, "false", 5)) { r = mk(json_type_boolean); s->p += 5; }
    else if (!strncmp(s->p, "null", 4)) { s->p += 4; }
    else s->p = NULL;
    if (s->p) s->depth--;
    return r;
}

json_object *json_tokener_parse(const char *str) {
    if (!str) return NULL;
    P s = { str, 0 };
    json_object *r = value(&s);
    if (!s.p) { json_object_put(r); return NULL; }
    ws(&s);
    if (*s.p) { json_object_put(r); return NULL; }
    return r;
}

int json_object_is_type(const json_object *o, json_type t) {
    if (!o) return t == json_type_null;
    return o->type == t;
}

json_type json_object_get_type(const json_object *o) { return o ? o->type : json_type_null; }

double json_object_get_double(const json_object *o) {
    if (!o) return 0;
    if (o->type == json_type_string && o->str) return strtod(o->str, NULL);
    return o->num;
}

int32_t json_object_get_int(const json_object *o) { return (int32_t)json_object_get_double(o); }

json_object *json_object_object_get(const json_object *o, const char *key) {
    json_object *v = NULL;
    json_object_object_get_ex(o, key, &v);
    return v;
}

int json_object_object_get_ex(const json_object *o, const char *key, json_object **value) {
    if (value) *value = NULL;
    if (!o || o->type != json_type_object || !key) return 0;
    for (int i = 0; i < o->n; i++) if (!strcmp(o->keys[i], key)) { if (value) *value = o->vals[i]; return 1; }
    return 0;
}

size_t json_object_array_length(const json_object *o) { return (o && o->type == json_type_array) ? (size_t)o->n : 0; }

json_object *json_object_array_get_idx(const json_object *o, size_t i) {
    if (!o || o->type != json_type_array || i >= (size_t)o->n) return NULL;
    return o->vals[i];
}
