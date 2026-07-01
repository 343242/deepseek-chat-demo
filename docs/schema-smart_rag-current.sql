--
-- PostgreSQL database dump
--

\restrict Do8eNDPvEvd1tFo8O2rRUSdrxno29cb8xU4GJV9dCVuqRm1yickD8HjkFaq1nkf

-- Dumped from database version 18.4 (Debian 18.4-1.pgdg12+1)
-- Dumped by pg_dump version 18.4 (Debian 18.4-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA public;


--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


--
-- Name: vector_store_content_tsv_trigger(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.vector_store_content_tsv_trigger() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.content_tsv := to_tsvector('jiebacfg', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: agent_session_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_session_event (
    id bigint NOT NULL,
    session_id character varying(36) NOT NULL,
    user_id bigint NOT NULL,
    event_type character varying(32) NOT NULL,
    priority smallint DEFAULT 3 NOT NULL,
    data jsonb NOT NULL,
    tool_name character varying(64),
    success boolean,
    duration_ms bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE agent_session_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.agent_session_event IS 'Agent 会话事件表 -- 记录每步事件的详细信息，供会话连续性恢复';


--
-- Name: COLUMN agent_session_event.session_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_session_event.session_id IS '会话 ID（UUIDv7），复用现有会话 ID';


--
-- Name: COLUMN agent_session_event.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_session_event.user_id IS '用户 ID，多租户隔离';


--
-- Name: COLUMN agent_session_event.event_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_session_event.event_type IS '事件类型：INTENT_CLASSIFIED / INTERMEDIATE_ANSWER / SELF_REFLECTION / RETRIEVAL_STRATEGY / TOOL_CALLED / GUARDRAIL_TRIGGERED';


--
-- Name: COLUMN agent_session_event.priority; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_session_event.priority IS '优先级：1=Critical（意图/答案/护栏）, 2=High（自省/策略）, 3=Normal（Tool 调用）';


--
-- Name: COLUMN agent_session_event.data; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_session_event.data IS '结构化事件数据（JSONB）';


--
-- Name: COLUMN agent_session_event.tool_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_session_event.tool_name IS 'Tool 名称（仅 TOOL_CALLED 事件有值）';


--
-- Name: COLUMN agent_session_event.success; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_session_event.success IS '是否成功（仅 TOOL_CALLED 事件有值）';


--
-- Name: COLUMN agent_session_event.duration_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.agent_session_event.duration_ms IS 'Tool 耗时 ms（仅 TOOL_CALLED 事件有值）';


--
-- Name: agent_session_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.agent_session_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: agent_session_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.agent_session_event_id_seq OWNED BY public.agent_session_event.id;


--
-- Name: conversation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversation (
    id bigint NOT NULL,
    conversation_id character varying(100) NOT NULL,
    user_id bigint NOT NULL,
    title character varying(200),
    title_source character varying(20) DEFAULT 'SYSTEM'::character varying,
    model_id character varying(100),
    pinned boolean DEFAULT false,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    message_count integer DEFAULT 0,
    last_message_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: conversation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.conversation ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.conversation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: evaluation_dataset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evaluation_dataset (
    id bigint NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    version integer DEFAULT 1 NOT NULL,
    source character varying(50) DEFAULT 'hybrid'::character varying NOT NULL,
    judge_model character varying(100),
    item_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: evaluation_dataset_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evaluation_dataset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evaluation_dataset_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evaluation_dataset_id_seq OWNED BY public.evaluation_dataset.id;


--
-- Name: evaluation_dataset_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evaluation_dataset_item (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    question text NOT NULL,
    ground_truth_answer text,
    relevant_chunk_ids text[],
    relevant_content text,
    tags character varying(100)[],
    status character varying(20) DEFAULT 'draft'::character varying NOT NULL,
    seq integer DEFAULT 0 NOT NULL,
    CONSTRAINT chk_eval_item_status CHECK (((status)::text = ANY ((ARRAY['draft'::character varying, 'approved'::character varying, 'rejected'::character varying])::text[])))
);


--
-- Name: evaluation_dataset_item_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evaluation_dataset_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evaluation_dataset_item_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evaluation_dataset_item_id_seq OWNED BY public.evaluation_dataset_item.id;


--
-- Name: evaluation_result; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evaluation_result (
    id bigint NOT NULL,
    run_id bigint NOT NULL,
    item_id bigint NOT NULL,
    item_question_snapshot text,
    item_ground_truth_snapshot text,
    item_relevant_chunk_ids_snapshot text[],
    query_rewritten text,
    retrieved_doc_ids text[],
    generated_answer text,
    stage_snapshots jsonb,
    retrieval_metrics jsonb,
    generation_metrics jsonb,
    error text,
    latency_ms integer
);


--
-- Name: evaluation_result_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evaluation_result_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evaluation_result_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evaluation_result_id_seq OWNED BY public.evaluation_result.id;


--
-- Name: evaluation_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evaluation_run (
    id bigint NOT NULL,
    dataset_id bigint NOT NULL,
    name character varying(200),
    config_snapshot jsonb,
    status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    generation_model character varying(100),
    judge_model character varying(100),
    summary jsonb,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_eval_run_status CHECK (((status)::text = ANY ((ARRAY['pending'::character varying, 'running'::character varying, 'completed'::character varying, 'failed'::character varying])::text[])))
);


--
-- Name: evaluation_run_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evaluation_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: evaluation_run_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evaluation_run_id_seq OWNED BY public.evaluation_run.id;


--
-- Name: message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message (
    id bigint NOT NULL,
    conversation_id character varying(100) NOT NULL,
    parent_id bigint,
    role character varying(20) NOT NULL,
    content text,
    status character varying(20) DEFAULT 'FINISHED'::character varying,
    model_id character varying(100),
    thinking_enabled boolean DEFAULT false,
    token_usage integer,
    duration_ms bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: message_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.message ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: model_params; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_params (
    id bigint NOT NULL,
    model_id character varying(128) NOT NULL,
    temperature double precision,
    max_tokens integer,
    top_p double precision,
    frequency_penalty double precision,
    presence_penalty double precision,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: model_params_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.model_params ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.model_params_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: rag_document; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rag_document (
    id bigint NOT NULL,
    file_name character varying(512) NOT NULL,
    file_size bigint,
    mime_type character varying(128),
    storage_key character varying(256) NOT NULL,
    bucket character varying(128) NOT NULL,
    user_id bigint,
    chunk_count integer,
    status character varying(32) DEFAULT 'UPLOADED'::character varying NOT NULL,
    error_message text,
    create_time timestamp with time zone DEFAULT now() NOT NULL,
    update_time timestamp with time zone DEFAULT now() NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    file_md5 character varying(32),
    team_id bigint,
    version integer DEFAULT 1 NOT NULL,
    superseded_by bigint,
    document_group_id character varying(36)
);


--
-- Name: COLUMN rag_document.file_md5; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rag_document.file_md5 IS '文件 MD5（服务端合并时计算），用于秒传校验';


--
-- Name: COLUMN rag_document.team_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rag_document.team_id IS '所属团队 ID（NULL=个人文档）';


--
-- Name: COLUMN rag_document.version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rag_document.version IS '文档版本号，替换时自增';


--
-- Name: COLUMN rag_document.superseded_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rag_document.superseded_by IS '被替代为哪个文档ID，NULL表示当前版本';


--
-- Name: COLUMN rag_document.document_group_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rag_document.document_group_id IS '文档逻辑标识，同一文档的不同版本共享（UUIDv7）';


--
-- Name: rag_document_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.rag_document ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.rag_document_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: spring_ai_chat_memory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.spring_ai_chat_memory (
    conversation_id character varying(36) NOT NULL,
    content text NOT NULL,
    type character varying(10) NOT NULL,
    "timestamp" timestamp without time zone NOT NULL,
    CONSTRAINT spring_ai_chat_memory_type_check CHECK (((type)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying, 'SYSTEM'::character varying, 'TOOL'::character varying])::text[])))
);


--
-- Name: sys_permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_permission (
    id bigint NOT NULL,
    permission_name character varying(128) NOT NULL,
    permission_desc character varying(256),
    resource_type character varying(32) NOT NULL,
    resource_key character varying(256) NOT NULL,
    parent_id bigint,
    status integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted integer DEFAULT 0 NOT NULL
);


--
-- Name: sys_permission_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.sys_permission ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sys_permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sys_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_role (
    id bigint NOT NULL,
    role_name character varying(64) NOT NULL,
    role_desc character varying(256),
    status integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted integer DEFAULT 0 NOT NULL
);


--
-- Name: sys_role_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.sys_role ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sys_role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sys_role_permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_role_permission (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);


--
-- Name: sys_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_user (
    id bigint NOT NULL,
    username character varying(64) NOT NULL,
    password character varying(256) NOT NULL,
    nickname character varying(64),
    email character varying(128),
    phone character varying(32),
    avatar character varying(512),
    status integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted integer DEFAULT 0 NOT NULL
);


--
-- Name: sys_user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_user_role (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL
);


--
-- Name: system_prompt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_prompt (
    id bigint NOT NULL,
    model_id character varying(128) NOT NULL,
    prompt_text text NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: system_prompt_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.system_prompt ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.system_prompt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: team; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.team (
    id bigint NOT NULL,
    team_name character varying(128) NOT NULL,
    team_desc character varying(512),
    creator_id bigint NOT NULL,
    default_upload_limit_mb bigint DEFAULT 50 NOT NULL,
    creator_upload_limit_mb bigint DEFAULT 200 NOT NULL,
    status smallint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL
);


--
-- Name: TABLE team; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.team IS '团队表';


--
-- Name: COLUMN team.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.team.status IS '0=INACTIVE 1=ACTIVE';


--
-- Name: COLUMN team.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.team.deleted IS '逻辑删除 0=正常 1=已删除';


--
-- Name: team_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.team ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: team_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.team_member (
    id bigint NOT NULL,
    team_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role smallint DEFAULT 10 NOT NULL,
    upload_limit_mb bigint DEFAULT 50 NOT NULL,
    status smallint DEFAULT 1 NOT NULL,
    joined_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE team_member; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.team_member IS '团队成员表';


--
-- Name: COLUMN team_member.role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.team_member.role IS '10=MEMBER(默认) 20=ADMIN 30=CREATOR';


--
-- Name: COLUMN team_member.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.team_member.status IS '0=INACTIVE 1=ACTIVE';


--
-- Name: team_member_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.team_member ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_member_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: team_upload_approval; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.team_upload_approval (
    id bigint NOT NULL,
    team_id bigint NOT NULL,
    document_id bigint NOT NULL,
    uploader_id bigint NOT NULL,
    status smallint DEFAULT 0 NOT NULL,
    reviewer_id bigint,
    review_comment character varying(512),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    reviewed_at timestamp with time zone
);


--
-- Name: TABLE team_upload_approval; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.team_upload_approval IS '团队上传审批表';


--
-- Name: COLUMN team_upload_approval.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.team_upload_approval.status IS '0=PENDING 1=APPROVED 2=REJECTED';


--
-- Name: team_upload_approval_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.team_upload_approval ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_upload_approval_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: token_usage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.token_usage (
    id bigint NOT NULL,
    conversation_id character varying(128) NOT NULL,
    model_id character varying(128) NOT NULL,
    prompt_tokens bigint,
    completion_tokens bigint,
    total_tokens bigint,
    duration_ms bigint,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: token_usage_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.token_usage ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.token_usage_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: vector_store; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vector_store (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    content text,
    metadata json,
    embedding public.vector(1024),
    content_tsv tsvector
);


--
-- Name: agent_session_event id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_session_event ALTER COLUMN id SET DEFAULT nextval('public.agent_session_event_id_seq'::regclass);


--
-- Name: evaluation_dataset id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_dataset ALTER COLUMN id SET DEFAULT nextval('public.evaluation_dataset_id_seq'::regclass);


--
-- Name: evaluation_dataset_item id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_dataset_item ALTER COLUMN id SET DEFAULT nextval('public.evaluation_dataset_item_id_seq'::regclass);


--
-- Name: evaluation_result id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_result ALTER COLUMN id SET DEFAULT nextval('public.evaluation_result_id_seq'::regclass);


--
-- Name: evaluation_run id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_run ALTER COLUMN id SET DEFAULT nextval('public.evaluation_run_id_seq'::regclass);


--
-- Name: agent_session_event agent_session_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_session_event
    ADD CONSTRAINT agent_session_event_pkey PRIMARY KEY (id);


--
-- Name: conversation conversation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation
    ADD CONSTRAINT conversation_pkey PRIMARY KEY (id);


--
-- Name: evaluation_dataset_item evaluation_dataset_item_dataset_id_seq_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_dataset_item
    ADD CONSTRAINT evaluation_dataset_item_dataset_id_seq_key UNIQUE (dataset_id, seq);


--
-- Name: evaluation_dataset_item evaluation_dataset_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_dataset_item
    ADD CONSTRAINT evaluation_dataset_item_pkey PRIMARY KEY (id);


--
-- Name: evaluation_dataset evaluation_dataset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_dataset
    ADD CONSTRAINT evaluation_dataset_pkey PRIMARY KEY (id);


--
-- Name: evaluation_result evaluation_result_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_result
    ADD CONSTRAINT evaluation_result_pkey PRIMARY KEY (id);


--
-- Name: evaluation_run evaluation_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_run
    ADD CONSTRAINT evaluation_run_pkey PRIMARY KEY (id);


--
-- Name: message message_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message
    ADD CONSTRAINT message_pkey PRIMARY KEY (id);


--
-- Name: model_params model_params_model_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_params
    ADD CONSTRAINT model_params_model_id_key UNIQUE (model_id);


--
-- Name: model_params model_params_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_params
    ADD CONSTRAINT model_params_pkey PRIMARY KEY (id);


--
-- Name: rag_document rag_document_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rag_document
    ADD CONSTRAINT rag_document_pkey PRIMARY KEY (id);


--
-- Name: sys_permission sys_permission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_permission
    ADD CONSTRAINT sys_permission_pkey PRIMARY KEY (id);


--
-- Name: sys_role_permission sys_role_permission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role_permission
    ADD CONSTRAINT sys_role_permission_pkey PRIMARY KEY (role_id, permission_id);


--
-- Name: sys_role sys_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role
    ADD CONSTRAINT sys_role_pkey PRIMARY KEY (id);


--
-- Name: sys_role sys_role_role_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role
    ADD CONSTRAINT sys_role_role_name_key UNIQUE (role_name);


--
-- Name: sys_user sys_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user
    ADD CONSTRAINT sys_user_pkey PRIMARY KEY (id);


--
-- Name: sys_user_role sys_user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_role
    ADD CONSTRAINT sys_user_role_pkey PRIMARY KEY (user_id, role_id);


--
-- Name: sys_user sys_user_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user
    ADD CONSTRAINT sys_user_username_key UNIQUE (username);


--
-- Name: system_prompt system_prompt_model_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_prompt
    ADD CONSTRAINT system_prompt_model_id_key UNIQUE (model_id);


--
-- Name: system_prompt system_prompt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_prompt
    ADD CONSTRAINT system_prompt_pkey PRIMARY KEY (id);


--
-- Name: team_member team_member_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_member
    ADD CONSTRAINT team_member_pkey PRIMARY KEY (id);


--
-- Name: team team_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team
    ADD CONSTRAINT team_pkey PRIMARY KEY (id);


--
-- Name: team_upload_approval team_upload_approval_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_upload_approval
    ADD CONSTRAINT team_upload_approval_pkey PRIMARY KEY (id);


--
-- Name: token_usage token_usage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.token_usage
    ADD CONSTRAINT token_usage_pkey PRIMARY KEY (id);


--
-- Name: conversation uk_conversation_cid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation
    ADD CONSTRAINT uk_conversation_cid UNIQUE (conversation_id);


--
-- Name: vector_store vector_store_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vector_store
    ADD CONSTRAINT vector_store_pkey PRIMARY KEY (id);


--
-- Name: idx_agent_event_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_event_session ON public.agent_session_event USING btree (session_id, created_at);


--
-- Name: idx_agent_event_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_event_type ON public.agent_session_event USING btree (session_id, event_type);


--
-- Name: idx_agent_event_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_agent_event_user ON public.agent_session_event USING btree (user_id, session_id);


--
-- Name: idx_approval_team_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_team_status ON public.team_upload_approval USING btree (team_id, status);


--
-- Name: idx_approval_uploader; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approval_uploader ON public.team_upload_approval USING btree (uploader_id);


--
-- Name: idx_conv_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_conv_user_status ON public.conversation USING btree (user_id, status, last_message_at DESC);


--
-- Name: idx_eval_item_dataset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_item_dataset ON public.evaluation_dataset_item USING btree (dataset_id);


--
-- Name: idx_eval_result_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_result_run ON public.evaluation_result USING btree (run_id);


--
-- Name: idx_eval_run_dataset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_run_dataset ON public.evaluation_run USING btree (dataset_id);


--
-- Name: idx_eval_run_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_eval_run_status ON public.evaluation_run USING btree (status);


--
-- Name: idx_msg_conv_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_conv_id ON public.message USING btree (conversation_id, created_at);


--
-- Name: idx_msg_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_parent ON public.message USING btree (parent_id);


--
-- Name: idx_rag_document_file_md5; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rag_document_file_md5 ON public.rag_document USING btree (file_md5) WHERE ((file_md5 IS NOT NULL) AND (deleted = 0));


--
-- Name: idx_rag_document_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rag_document_group ON public.rag_document USING btree (document_group_id) WHERE (deleted = 0);


--
-- Name: idx_rag_document_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rag_document_status ON public.rag_document USING btree (status);


--
-- Name: idx_rag_document_superseded_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rag_document_superseded_by ON public.rag_document USING btree (superseded_by) WHERE (superseded_by IS NOT NULL);


--
-- Name: idx_rag_document_team; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rag_document_team ON public.rag_document USING btree (team_id);


--
-- Name: idx_rag_document_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rag_document_user ON public.rag_document USING btree (user_id);


--
-- Name: idx_team_creator; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_team_creator ON public.team USING btree (creator_id);


--
-- Name: idx_team_member_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_team_member_user ON public.team_member USING btree (user_id, status);


--
-- Name: idx_token_usage_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_token_usage_conversation ON public.token_usage USING btree (conversation_id);


--
-- Name: idx_token_usage_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_token_usage_created ON public.token_usage USING btree (created_at);


--
-- Name: idx_token_usage_model; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_token_usage_model ON public.token_usage USING btree (model_id);


--
-- Name: idx_vector_store_content_tsv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vector_store_content_tsv ON public.vector_store USING gin (content_tsv);


--
-- Name: spring_ai_chat_memory_conversation_id_timestamp_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_ai_chat_memory_conversation_id_timestamp_idx ON public.spring_ai_chat_memory USING btree (conversation_id, "timestamp");


--
-- Name: spring_ai_vector_index; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_ai_vector_index ON public.vector_store USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: uk_team_name_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_team_name_active ON public.team USING btree (team_name) WHERE (deleted = 0);


--
-- Name: uk_team_user_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_team_user_active ON public.team_member USING btree (team_id, user_id) WHERE (status = 1);


--
-- Name: uq_rag_document_group_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_rag_document_group_version ON public.rag_document USING btree (document_group_id, version) WHERE ((deleted = 0) AND (document_group_id IS NOT NULL));


--
-- Name: vector_store_embedding_hnsw_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX vector_store_embedding_hnsw_idx ON public.vector_store USING hnsw (embedding public.vector_cosine_ops) WITH (m='32', ef_construction='128');


--
-- Name: vector_store trg_vector_store_content_tsv; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_vector_store_content_tsv BEFORE INSERT OR UPDATE OF content ON public.vector_store FOR EACH ROW EXECUTE FUNCTION public.vector_store_content_tsv_trigger();


--
-- Name: evaluation_dataset_item evaluation_dataset_item_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_dataset_item
    ADD CONSTRAINT evaluation_dataset_item_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.evaluation_dataset(id) ON DELETE CASCADE;


--
-- Name: evaluation_result evaluation_result_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_result
    ADD CONSTRAINT evaluation_result_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.evaluation_dataset_item(id);


--
-- Name: evaluation_result evaluation_result_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_result
    ADD CONSTRAINT evaluation_result_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.evaluation_run(id) ON DELETE CASCADE;


--
-- Name: evaluation_run evaluation_run_dataset_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evaluation_run
    ADD CONSTRAINT evaluation_run_dataset_id_fkey FOREIGN KEY (dataset_id) REFERENCES public.evaluation_dataset(id);


--
-- Name: message fk_message_parent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message
    ADD CONSTRAINT fk_message_parent FOREIGN KEY (parent_id) REFERENCES public.message(id) ON DELETE SET NULL;


--
-- Name: sys_role_permission sys_role_permission_permission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role_permission
    ADD CONSTRAINT sys_role_permission_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.sys_permission(id);


--
-- Name: sys_role_permission sys_role_permission_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_role_permission
    ADD CONSTRAINT sys_role_permission_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.sys_role(id);


--
-- Name: sys_user_role sys_user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_role
    ADD CONSTRAINT sys_user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.sys_role(id);


--
-- Name: sys_user_role sys_user_role_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_role
    ADD CONSTRAINT sys_user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.sys_user(id);


--
-- Name: team team_creator_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team
    ADD CONSTRAINT team_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES public.sys_user(id);


--
-- Name: team_member team_member_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_member
    ADD CONSTRAINT team_member_team_id_fkey FOREIGN KEY (team_id) REFERENCES public.team(id);


--
-- Name: team_member team_member_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_member
    ADD CONSTRAINT team_member_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.sys_user(id);


--
-- Name: team_upload_approval team_upload_approval_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_upload_approval
    ADD CONSTRAINT team_upload_approval_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.rag_document(id);


--
-- Name: team_upload_approval team_upload_approval_reviewer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_upload_approval
    ADD CONSTRAINT team_upload_approval_reviewer_id_fkey FOREIGN KEY (reviewer_id) REFERENCES public.sys_user(id);


--
-- Name: team_upload_approval team_upload_approval_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_upload_approval
    ADD CONSTRAINT team_upload_approval_team_id_fkey FOREIGN KEY (team_id) REFERENCES public.team(id);


--
-- Name: team_upload_approval team_upload_approval_uploader_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.team_upload_approval
    ADD CONSTRAINT team_upload_approval_uploader_id_fkey FOREIGN KEY (uploader_id) REFERENCES public.sys_user(id);


--
-- PostgreSQL database dump complete
--

\unrestrict Do8eNDPvEvd1tFo8O2rRUSdrxno29cb8xU4GJV9dCVuqRm1yickD8HjkFaq1nkf

