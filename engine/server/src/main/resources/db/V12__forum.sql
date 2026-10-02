-- Forum upgrade (02.10, the owner chose to keep our own forum): what each reader has read,
-- topics followed (replies show up as notifications), search over titles and posts.
CREATE TABLE forum_reads (
    account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    topic_id   BIGINT NOT NULL REFERENCES forum_topics (id) ON DELETE CASCADE,
    last_post  BIGINT NOT NULL,
    PRIMARY KEY (account_id, topic_id)
);

CREATE TABLE forum_follows (
    account_id BIGINT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    topic_id   BIGINT NOT NULL REFERENCES forum_topics (id) ON DELETE CASCADE,
    PRIMARY KEY (account_id, topic_id)
);
CREATE INDEX forum_follows_topic ON forum_follows (topic_id);
