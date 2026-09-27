package dev.ceseasons.season;

/** The twelve equal-length parts of a season year, in calendar order. */
public enum SubSeason {
    EARLY_SPRING(Season.SPRING),
    MID_SPRING(Season.SPRING),
    LATE_SPRING(Season.SPRING),
    EARLY_SUMMER(Season.SUMMER),
    MID_SUMMER(Season.SUMMER),
    LATE_SUMMER(Season.SUMMER),
    EARLY_AUTUMN(Season.AUTUMN),
    MID_AUTUMN(Season.AUTUMN),
    LATE_AUTUMN(Season.AUTUMN),
    EARLY_WINTER(Season.WINTER),
    MID_WINTER(Season.WINTER),
    LATE_WINTER(Season.WINTER);

    private final Season season;

    SubSeason(Season season) {
        this.season = season;
    }

    public Season season() {
        return season;
    }
}
