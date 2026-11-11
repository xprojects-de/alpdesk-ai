"""Measures of a person's posture from the points RTMPose finds (rtmlib's BodyWithFeet, Halpe-26) - in the video image
only (docs/architecture/code-execution.md "Videos"). The same definitions in every answer, so two measurements can be
compared: an angle at a joint is 180 when the limb is straight; image coordinates grow to the right and downwards.

    import cv2, posture
    from rtmlib import BodyWithFeet
    image = cv2.imread("out/frame_01.jpg")
    keypoints, scores = BodyWithFeet()(image)
    points = posture.person(keypoints, scores)      # the largest person, sure points only
    values = posture.measure(points)                # {"knee_left": 118.4, "trunk_lean": 31.0, ...}
    posture.draw(image, points, values)             # skeleton and angles drawn in
"""

import math

# Halpe-26: where each point is in a person's 26.
POINTS = {
    "nose": 0, "left_eye": 1, "right_eye": 2, "left_ear": 3, "right_ear": 4,
    "left_shoulder": 5, "right_shoulder": 6, "left_elbow": 7, "right_elbow": 8, "left_wrist": 9, "right_wrist": 10,
    "left_hip": 11, "right_hip": 12, "left_knee": 13, "right_knee": 14, "left_ankle": 15, "right_ankle": 16,
    "head": 17, "neck": 18, "hip": 19, "left_big_toe": 20, "right_big_toe": 21, "left_small_toe": 22,
    "right_small_toe": 23, "left_heel": 24, "right_heel": 25,
}
# Below it a point is a guess - the person may be covered there or out of the picture.
MIN_SCORE = 0.3
BONES = [
    ("left_shoulder", "right_shoulder"), ("left_hip", "right_hip"), ("left_shoulder", "left_hip"),
    ("right_shoulder", "right_hip"), ("left_shoulder", "left_elbow"), ("left_elbow", "left_wrist"),
    ("right_shoulder", "right_elbow"), ("right_elbow", "right_wrist"), ("left_hip", "left_knee"),
    ("left_knee", "left_ankle"), ("right_hip", "right_knee"), ("right_knee", "right_ankle"),
    ("left_ankle", "left_heel"), ("left_ankle", "left_big_toe"), ("left_heel", "left_big_toe"),
    ("right_ankle", "right_heel"), ("right_ankle", "right_big_toe"), ("right_heel", "right_big_toe"),
    ("neck", "head"),
]
# Where an angle is drawn, and the three points it is measured at (the middle one is the joint).
JOINT_ANGLES = {
    "knee_left": ("left_hip", "left_knee", "left_ankle"),
    "knee_right": ("right_hip", "right_knee", "right_ankle"),
    "hip_left": ("left_shoulder", "left_hip", "left_knee"),
    "hip_right": ("right_shoulder", "right_hip", "right_knee"),
    "elbow_left": ("left_shoulder", "left_elbow", "left_wrist"),
    "elbow_right": ("right_shoulder", "right_elbow", "right_wrist"),
}


def largest(keypoints, scores):
    """The index of the person whose sure points span the largest box - the one nearest the camera; None for nobody."""
    best, best_size = None, 0
    for index, (points, score) in enumerate(zip(keypoints, scores)):
        sure = [(float(p[0]), float(p[1])) for p, s in zip(points, score) if s >= MIN_SCORE]
        if len(sure) < 4:
            continue
        xs, ys = [p[0] for p in sure], [p[1] for p in sure]
        size = (max(xs) - min(xs)) * (max(ys) - min(ys))
        if size > best_size:
            best, best_size = index, size
    return best


def person(keypoints, scores, index=None):
    """One person's sure points by name: {"left_knee": (x, y), ...} - the largest unless an index is given. Empty when
    nobody was found."""
    if index is None:
        index = largest(keypoints, scores)
    if index is None or index >= len(keypoints):
        return {}
    return {name: (float(keypoints[index][i][0]), float(keypoints[index][i][1]))
            for name, i in POINTS.items() if scores[index][i] >= MIN_SCORE}


def angle(a, b, c):
    """The angle at b between b-a and b-c in degrees, 180 = straight; None when a point is missing."""
    if a is None or b is None or c is None:
        return None
    v1, v2 = (a[0] - b[0], a[1] - b[1]), (c[0] - b[0], c[1] - b[1])
    length = math.hypot(*v1) * math.hypot(*v2)
    if length == 0:
        return None
    cosine = max(-1.0, min(1.0, (v1[0] * v2[0] + v1[1] * v2[1]) / length))
    return round(math.degrees(math.acos(cosine)), 1)


def middle(points, left, right):
    a, b = points.get(left), points.get(right)
    if a and b:
        return (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    return a or b


def lean(lower, upper):
    """How far the line from lower to upper leans from the vertical, in degrees (0 = upright, 90 = level), and to which
    side of the picture its upper end lies - None when a point is missing."""
    if lower is None or upper is None or lower == upper:
        return None, None
    dx, dy = upper[0] - lower[0], lower[1] - upper[1]
    degrees = round(math.degrees(math.atan2(abs(dx), dy)), 1)
    return degrees, ("right" if dx > 0 else "left") if degrees >= 1 else "upright"


def tilt(points, left, right):
    """How far the line between two points (shoulders, hips) is tilted from level, in degrees, and which end is lower."""
    a, b = points.get(left), points.get(right)
    if not (a and b) or a == b:
        return None, None
    degrees = round(math.degrees(math.atan2(abs(a[1] - b[1]), abs(a[0] - b[0]))), 1)
    return degrees, ("left" if a[1] > b[1] else "right") if degrees >= 1 else "level"


def measure(points):
    """The posture's values; None where a point was not sure. Angles at joints: 180 = straight. trunk_lean: the trunk
    (hips to shoulders) from the vertical; body_lean: the whole body (feet to hips) from the vertical - in a turn it is
    largest in its middle and passes 0 when the body crosses over; both with the side of the picture they lean to.
    hips_over_feet: the hips' horizontal distance from the feet in leg lengths, + to the right of the picture - which
    side is "behind" depends on the direction of travel. shoulder_tilt, hip_tilt: from level, with the lower end."""
    values = {name: angle(points.get(a), points.get(b), points.get(c)) for name, (a, b, c) in JOINT_ANGLES.items()}
    shoulders = middle(points, "left_shoulder", "right_shoulder")
    hips = middle(points, "left_hip", "right_hip")
    feet = middle(points, "left_ankle", "right_ankle")
    values["trunk_lean"], values["trunk_lean_side"] = lean(hips, shoulders)
    values["body_lean"], values["body_lean_side"] = lean(feet, hips)
    values["hips_over_feet"] = None
    if hips and feet and math.dist(hips, feet) > 0:
        values["hips_over_feet"] = round((hips[0] - feet[0]) / math.dist(hips, feet), 2)
    values["shoulder_tilt"], values["shoulder_lower"] = tilt(points, "left_shoulder", "right_shoulder")
    values["hip_tilt"], values["hip_lower"] = tilt(points, "left_hip", "right_hip")
    return values


def draw(image, points, values=None, color=(0, 200, 255), thickness=2):
    """The skeleton onto the image (BGR, as cv2 reads it), and the joint angles of values next to their joints - in
    the image's own pixels. Returns the image."""
    import cv2
    for a, b in BONES:
        if a in points and b in points:
            cv2.line(image, tuple(map(int, points[a])), tuple(map(int, points[b])), color, thickness, cv2.LINE_AA)
    for x, y in points.values():
        cv2.circle(image, (int(x), int(y)), thickness + 2, color, -1, cv2.LINE_AA)
    scale = max(0.4, image.shape[1] / 1600)
    for name, (_, joint, _) in JOINT_ANGLES.items():
        value = (values or {}).get(name)
        if value is None or joint not in points:
            continue
        x, y = int(points[joint][0]) + 8, int(points[joint][1]) - 8
        text = f"{value:.0f}"
        cv2.putText(image, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, scale, (0, 0, 0), thickness + 2, cv2.LINE_AA)
        cv2.putText(image, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, scale, (255, 255, 255), 1, cv2.LINE_AA)
    return image
