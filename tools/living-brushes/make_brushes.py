import json, math, sys, os
out = sys.argv[1]
def s(base, **inputs):
    return {"base_value": base, "inputs": {k: v for k, v in inputs.items()}}
P = lambda *pts: [list(p) for p in pts]
# all original Inkora designs
B = {
 "rain_wash": {  # watercolor: thin, wet, carries the colour it passes over, pigment mixing
   "radius_logarithmic": s(math.log(20), pressure=P((0,-0.45),(1,0.25))),
   "opaque": s(1.0), "opaque_multiply": s(0, pressure=P((0,0.15),(0.4,0.55),(1,0.95))), "opaque_linearize": s(0.55),
   "hardness": s(0.4), "softness": s(0.1), "dabs_per_actual_radius": s(5), "dabs_per_basic_radius": s(0),
   "smudge": s(0.32), "smudge_length": s(0.8), "smudge_radius_log": s(0.3),
   "paint_mode": s(1.0), "offset_by_random": s(0.08), "radius_by_random": s(0.04), "tracking_noise": s(0.12),
   "change_color_v": s(0.0, random=P((0,-0.015),(1,0.015)))},
 "gouache": {  # flat, creamy, opaque, mixes a little with wet paint
   "radius_logarithmic": s(math.log(14), pressure=P((0,-0.35),(1,0.2))),
   "opaque": s(1.0), "opaque_multiply": s(0, pressure=P((0,0.2),(0.3,0.85),(1,1))),
   "hardness": s(0.8), "softness": s(0.15), "dabs_per_actual_radius": s(6),
   "smudge": s(0.4), "smudge_length": s(0.7), "paint_mode": s(1.0), "offset_by_random": s(0.03)},
 "oil_drag": {  # loaded oil brush: pulls wet paint along, flat bristle shape
   "radius_logarithmic": s(math.log(16), pressure=P((0,-0.3),(1,0.15))),
   "opaque": s(1.0), "opaque_multiply": s(0, pressure=P((0,0.4),(0.3,0.95),(1,1))),
   "hardness": s(0.75), "softness": s(0.2), "dabs_per_actual_radius": s(6),
   "smudge": s(0.62, pressure=P((0,0.15),(1,-0.3))), "smudge_length": s(0.9), "smudge_radius_log": s(0.15),
   "paint_mode": s(1.0), "elliptical_dab_ratio": s(2.2), "elliptical_dab_angle": s(90), "direction_filter": s(2.5),
   "offset_by_random": s(0.04), "radius_by_random": s(0.03)},
 "charcoal": {  # grainy, breaks up, darker with pressure
   "radius_logarithmic": s(math.log(4), pressure=P((0,-0.2),(1,0.35))),
   "opaque": s(0.3), "opaque_multiply": s(0, pressure=P((0,0),(0.5,0.45),(1,1))),
   "hardness": s(0.55), "dabs_per_actual_radius": s(10),
   "offset_by_random": s(0.9), "radius_by_random": s(0.12), "smudge": s(0.06), "smudge_length": s(0.9),
   "tracking_noise": s(0.2), "anti_aliasing": s(0.6)},
 "living_ink": {  # wet ink: swells with pressure, thins when fast, smooth
   "radius_logarithmic": s(math.log(4.2), pressure=P((0,-1.1),(0.5,0),(1,0.45)), speed1=P((0,0),(4,-0.55))),
   "opaque": s(1.0), "opaque_multiply": s(0, pressure=P((0,0.6),(0.15,1),(1,1))),
   "hardness": s(0.96), "softness": s(0.05), "anti_aliasing": s(1.2), "dabs_per_actual_radius": s(4),
   "slow_tracking": s(1.2), "speed1_slowness": s(0.05), "speed1_gamma": s(3.5)},
 "blender": {  # smears the paint, adds none
   "radius_logarithmic": s(math.log(18), pressure=P((0,-0.4),(1,0.2))),
   "opaque": s(1.0), "opaque_multiply": s(0, pressure=P((0,0.1),(1,1))),
   "hardness": s(0.3), "softness": s(0.4), "dabs_per_actual_radius": s(5),
   "smudge": s(1.0), "smudge_length": s(0.6), "paint_mode": s(1.0)},
 "soft_pastel": {  # chalky, a little colour noise
   "radius_logarithmic": s(math.log(9), pressure=P((0,-0.3),(1,0.2))),
   "opaque": s(0.3), "opaque_multiply": s(0, pressure=P((0,0),(1,1))),
   "hardness": s(0.5), "dabs_per_actual_radius": s(10), "offset_by_random": s(0.6), "radius_by_random": s(0.1),
   "smudge": s(0.25), "smudge_length": s(0.7), "paint_mode": s(1.0),
   "change_color_v": s(0, random=P((0,-0.04),(1,0.04))), "change_color_hsv_s": s(0, random=P((0,-0.05),(1,0.05)))},
 "graphite": {  # pencil: thin, grey by pressure, rough
   "radius_logarithmic": s(math.log(1.6), pressure=P((0,-0.1),(1,0.2))),
   "opaque": s(0.6), "opaque_multiply": s(0, pressure=P((0,0),(0.6,0.5),(1,1))),
   "hardness": s(0.7), "anti_aliasing": s(1.0), "dabs_per_actual_radius": s(5),
   "offset_by_random": s(0.4), "radius_by_random": s(0.1), "tracking_noise": s(0.05)},
 "dry_bristle": {  # half-dry brush: broken streaks
   "radius_logarithmic": s(math.log(3), pressure=P((0,-0.2),(1,0.25))),
   "opaque": s(0.85), "opaque_multiply": s(0, pressure=P((0,0.2),(1,1))),
   "hardness": s(0.85), "dabs_per_actual_radius": s(3), "dabs_per_second": s(0),
   "offset_by_random": s(1.6), "radius_by_random": s(0.1), "smudge": s(0.3), "smudge_length": s(0.85), "paint_mode": s(1.0),
   "elliptical_dab_ratio": s(3), "elliptical_dab_angle": s(0), "direction_filter": s(1.5)},
 "airbrush": {  # soft spray that builds up
   "radius_logarithmic": s(math.log(30)), "opaque": s(0.3), "opaque_linearize": s(0.2), "opaque_multiply": s(0, pressure=P((0,0),(1,1))),
   "hardness": s(0.25), "dabs_per_actual_radius": s(4), "dabs_per_second": s(20)},
 "felt_marker": {  # marker: overlaps darken, crisp edge
   "radius_logarithmic": s(math.log(7)), "opaque": s(0.55), "opaque_multiply": s(0, pressure=P((0,0.6),(0.2,1),(1,1))), "opaque_linearize": s(0.0),
   "hardness": s(0.88), "dabs_per_actual_radius": s(3), "smudge": s(0.2), "smudge_length": s(0.3), "paint_mode": s(1.0)},
 "spatter": {  # flicked drops
   "radius_logarithmic": s(math.log(2.5)), "opaque": s(0.95), "opaque_multiply": s(0, pressure=P((0,1),(1,1))), "opaque_linearize": s(0.0), "hardness": s(0.9),
   "dabs_per_actual_radius": s(0.6), "dabs_per_second": s(25),
   "offset_by_random": s(5.0, pressure=P((0,0),(1,2))), "radius_by_random": s(0.45)},
}
os.makedirs(out, exist_ok=True)
for name, st in B.items():
    # tuned with no linearizing unless the brush says so (the engine's own default is 0.9)
    st.setdefault("opaque_linearize", s(0.0))
    json.dump({"version": 3, "comment": "Inkora original brush", "group": "", "parent_brush_name": "", "settings": st},
              open(os.path.join(out, name + ".myb"), "w"), indent=1)
print(len(B))
