package com.winllc.dsp.model;

import java.util.List;

public record EntryListResponse(List<DirectoryEntry> entries, int total, int page, int pageSize, boolean truncated) {}
