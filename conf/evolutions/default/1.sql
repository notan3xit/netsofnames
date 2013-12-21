
# --- !Ups

CREATE TABLE "entities" (
  "id" BIGSERIAL PRIMARY KEY,
  "type" INT NOT NULL,
  "name" VARCHAR(255) NOT NULL,
  "frequency" INT NOT NULL
);

CREATE TABLE "relationships" (
  "id" BIGSERIAL PRIMARY KEY,
  "type" INT NOT NULL,
  "entity1" BIGINT NOT NULL, -- REFERENCES entities(id)
  "entity2" BIGINT NOT NULL, -- REFERENCES entities(id)
  "frequency" INT NOT NULL
);

CREATE TABLE "sentences" (
  "id" BIGSERIAL PRIMARY KEY,
  "sentence" TEXT NOT NULL
);

CREATE TABLE "sources" (
  "id" BIGSERIAL PRIMARY KEY,
  "source" VARCHAR(255) NOT NULL,
  "date" DATE NOT NULL
);

CREATE TABLE "relationships_to_sentences" (
  "relationship_id" BIGINT NOT NULL, -- REFERENCES relationships(id)
  "sentence_id" BIGINT NOT NULL -- REFERENCES sentences(id)
);

CREATE TABLE "sentences_to_sources" (
  "sentence_id" BIGINT NOT NULL, -- REFERENCES sentences(id)
  "source_id" BIGINT NOT NULL -- REFERENCES sources(id)
);

# --- !Downs

DROP TABLE "entities";
DROP TABLE "relationships";
DROP TABLE "sentences";
DROP TABLE "sources";
DROP TABLE "relationships_to_sentences";
DROP TABLE "sentences_to_sources";