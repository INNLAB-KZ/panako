-- One retained unread request anchors a bounded batch of byte-identical duplicates.
-- All preconditions are checked atomically before the single XDEL operation.
local stream = KEYS[1]
local group = ARGV[1]
local mode = ARGV[2]
local item = cjson.decode(ARGV[3])
if mode ~= 'plan' and mode ~= 'apply' then
    return redis.error_reply('Invalid mode')
end
if #item.ids < 1 or #item.ids > 100 or #item.fields ~= 4 then
    return redis.error_reply('Invalid bounded batch')
end

local function greater(a, b)
    local am, as = string.match(a, '^(%d+)%-(%d+)$')
    local bm, bs = string.match(b, '^(%d+)%-(%d+)$')
    if not am or not bm then error('Invalid stream ID') end
    if #am ~= #bm then return #am > #bm end
    if am ~= bm then return am > bm end
    if #as ~= #bs then return #as > #bs end
    return as > bs
end

local function same_fields(actual)
    if #actual ~= #item.fields then return false end
    local expected = {}
    for i = 1, #item.fields, 2 do
        if expected[item.fields[i]] then return false end
        expected[item.fields[i]] = item.fields[i + 1]
    end
    if not expected.key or not expected.value then return false end
    for i = 1, #actual, 2 do
        if expected[actual[i]] ~= actual[i + 1] then return false end
        expected[actual[i]] = nil
    end
    return next(expected) == nil
end

local groups = redis.call('XINFO', 'GROUPS', stream)
if #groups ~= 1 then return redis.error_reply('Consumer group set changed') end
local cfg = {}
for i = 1, #groups[1], 2 do cfg[groups[1][i]] = groups[1][i + 1] end
if cfg.name ~= group then return redis.error_reply('Unexpected consumer group') end
local cursor = cfg['last-delivered-id']
local function skipped(reason)
    return cjson.encode({reason = reason, eligible = 0, deleted = 0})
end
if not greater(item.keep_id, cursor) then return skipped('keeper_already_delivered') end
local keeper = redis.call('XRANGE', stream, item.keep_id, item.keep_id)
if #keeper == 0 then return skipped('keeper_missing') end
if not same_fields(keeper[1][2]) then return redis.error_reply('Keeper payload mismatch') end
if #redis.call('XPENDING', stream, group, item.keep_id, item.keep_id, 1) > 0 then
    return skipped('keeper_pending')
end
local ids = {}
local seen = {}
for _, id in ipairs(item.ids) do
    if not greater(id, item.keep_id) or seen[id] then
        return redis.error_reply('Invalid duplicate ID ordering')
    end
    seen[id] = true
    local rows = redis.call('XRANGE', stream, id, id)
    if #rows > 0 then
        if not same_fields(rows[1][2]) then return redis.error_reply('Duplicate payload mismatch') end
        if greater(id, cursor) and #redis.call('XPENDING', stream, group, id, id, 1) == 0 then
            table.insert(ids, id)
        end
    end
end
local deleted = 0
if mode == 'apply' and #ids > 0 then
    deleted = redis.call('XDEL', stream, unpack(ids))
    if deleted ~= #ids then return redis.error_reply('Unexpected deletion count') end
end
return cjson.encode({reason = 'checked', eligible = #ids, deleted = deleted, ids = ids})
