package com.ltm.geoduel.server.model;

import java.util.ArrayList;
import java.util.List;

/** Một địa điểm cùng danh sách ảnh manh mối (thứ tự sort_order). */
public class LocationData {
    /** Một ảnh manh mối: đường dẫn tương đối + có phải ảnh toàn cảnh 360° không. */
    public record ClueImage(String path, boolean pano) {}

    public int id;
    public String slug;
    public String name;
    public String country;
    public double targetLat;
    public double targetLng;
    public final List<ClueImage> images = new ArrayList<>();
}
