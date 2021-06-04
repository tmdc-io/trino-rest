/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pl.net.was.rest.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.LimitApplicationResult;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.SortItem;
import io.trino.spi.connector.TopNApplicationResult;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.TypeOperators;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import pl.net.was.rest.Rest;
import pl.net.was.rest.RestColumnHandle;
import pl.net.was.rest.RestTableHandle;
import pl.net.was.rest.github.filter.ArtifactFilter;
import pl.net.was.rest.github.filter.FilterApplier;
import pl.net.was.rest.github.filter.IssueCommentFilter;
import pl.net.was.rest.github.filter.IssueFilter;
import pl.net.was.rest.github.filter.JobFilter;
import pl.net.was.rest.github.filter.OrgFilter;
import pl.net.was.rest.github.filter.PullCommitFilter;
import pl.net.was.rest.github.filter.PullFilter;
import pl.net.was.rest.github.filter.RepoFilter;
import pl.net.was.rest.github.filter.ReviewCommentFilter;
import pl.net.was.rest.github.filter.ReviewFilter;
import pl.net.was.rest.github.filter.RunFilter;
import pl.net.was.rest.github.filter.StepFilter;
import pl.net.was.rest.github.filter.UserFilter;
import pl.net.was.rest.github.function.BaseFunction;
import pl.net.was.rest.github.model.Artifact;
import pl.net.was.rest.github.model.ArtifactsList;
import pl.net.was.rest.github.model.Envelope;
import pl.net.was.rest.github.model.Job;
import pl.net.was.rest.github.model.JobsList;
import pl.net.was.rest.github.model.Organization;
import pl.net.was.rest.github.model.Repository;
import pl.net.was.rest.github.model.Step;
import pl.net.was.rest.github.model.User;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.INVALID_ROW_FILTER;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.lang.String.format;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static pl.net.was.rest.github.function.Artifacts.download;

public class GithubRest
        implements Rest
{
    Logger log = Logger.getLogger(GithubRest.class.getName());
    public static final String SCHEMA_NAME = "default";

    private final int PER_PAGE = 100;

    private static String token;
    private final GithubService service = getService();

    public static final Map<String, List<ColumnMetadata>> columns = new ImmutableMap.Builder<String, List<ColumnMetadata>>()
            .put("orgs", ImmutableList.of(
                    new ColumnMetadata("login", VARCHAR),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("description", VARCHAR),
                    new ColumnMetadata("name", VARCHAR),
                    new ColumnMetadata("company", VARCHAR),
                    new ColumnMetadata("blog", VARCHAR),
                    new ColumnMetadata("location", VARCHAR),
                    new ColumnMetadata("email", VARCHAR),
                    new ColumnMetadata("twitter_username", VARCHAR),
                    new ColumnMetadata("is_verified", BOOLEAN),
                    new ColumnMetadata("has_organization_projects", BOOLEAN),
                    new ColumnMetadata("has_repository_projects", BOOLEAN),
                    new ColumnMetadata("public_repos", BIGINT),
                    new ColumnMetadata("public_gists", BIGINT),
                    new ColumnMetadata("followers", BIGINT),
                    new ColumnMetadata("following", BIGINT),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("type", VARCHAR),
                    new ColumnMetadata("total_private_repos", BIGINT),
                    new ColumnMetadata("owned_private_repos", BIGINT),
                    new ColumnMetadata("private_gists", BIGINT),
                    new ColumnMetadata("disk_usage", BIGINT),
                    new ColumnMetadata("collaborators", BIGINT),
                    new ColumnMetadata("billing_email", VARCHAR),
                    new ColumnMetadata("default_repository_permission", VARCHAR),
                    new ColumnMetadata("members_can_create_repositories", BOOLEAN),
                    new ColumnMetadata("two_factor_requirement_enabled", BOOLEAN),
                    new ColumnMetadata("members_allowed_repository_creation_type", VARCHAR),
                    new ColumnMetadata("members_can_create_public_repositories", BOOLEAN),
                    new ColumnMetadata("members_can_create_private_repositories", BOOLEAN),
                    new ColumnMetadata("members_can_create_internal_repositories", BOOLEAN),
                    new ColumnMetadata("members_can_create_pages", BOOLEAN)))
            .put("users", ImmutableList.of(
                    new ColumnMetadata("login", VARCHAR),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("avatar_url", VARCHAR),
                    new ColumnMetadata("gravatar_id", VARCHAR),
                    new ColumnMetadata("type", VARCHAR),
                    new ColumnMetadata("site_admin", BOOLEAN),
                    new ColumnMetadata("name", VARCHAR),
                    new ColumnMetadata("company", VARCHAR),
                    new ColumnMetadata("blog", VARCHAR),
                    new ColumnMetadata("location", VARCHAR),
                    new ColumnMetadata("email", VARCHAR),
                    new ColumnMetadata("hireable", BOOLEAN),
                    new ColumnMetadata("bio", VARCHAR),
                    new ColumnMetadata("twitter_username", VARCHAR),
                    new ColumnMetadata("public_repos", BIGINT),
                    new ColumnMetadata("public_gists", BIGINT),
                    new ColumnMetadata("followers", BIGINT),
                    new ColumnMetadata("following", BIGINT),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3))))
            .put("repos", ImmutableList.of(
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("name", VARCHAR),
                    new ColumnMetadata("full_name", VARCHAR),
                    new ColumnMetadata("owner_id", BIGINT),
                    new ColumnMetadata("owner_login", VARCHAR),
                    new ColumnMetadata("private", BOOLEAN),
                    new ColumnMetadata("description", VARCHAR),
                    new ColumnMetadata("fork", BOOLEAN),
                    new ColumnMetadata("homepage", VARCHAR),
                    new ColumnMetadata("url", VARCHAR),
                    new ColumnMetadata("forks_count", BIGINT),
                    new ColumnMetadata("stargazers_count", BIGINT),
                    new ColumnMetadata("watchers_count", BIGINT),
                    new ColumnMetadata("size", BIGINT),
                    new ColumnMetadata("default_branch", VARCHAR),
                    new ColumnMetadata("open_issues_count", BIGINT),
                    new ColumnMetadata("is_template", BOOLEAN),
                    new ColumnMetadata("topics", new ArrayType(VARCHAR)),
                    new ColumnMetadata("has_issues", BOOLEAN),
                    new ColumnMetadata("has_projects", BOOLEAN),
                    new ColumnMetadata("has_wiki", BOOLEAN),
                    new ColumnMetadata("has_pages", BOOLEAN),
                    new ColumnMetadata("has_downloads", BOOLEAN),
                    new ColumnMetadata("archived", BOOLEAN),
                    new ColumnMetadata("disabled", BOOLEAN),
                    new ColumnMetadata("visibility", VARCHAR),
                    new ColumnMetadata("pushed_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("permissions", new MapType(VARCHAR, BOOLEAN, new TypeOperators()))))
            .put("pulls", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("number", BIGINT),
                    new ColumnMetadata("state", VARCHAR),
                    new ColumnMetadata("locked", BOOLEAN),
                    new ColumnMetadata("title", VARCHAR),
                    new ColumnMetadata("user_id", BIGINT),
                    new ColumnMetadata("user_login", VARCHAR),
                    new ColumnMetadata("body", VARCHAR),
                    new ColumnMetadata("label_ids", new ArrayType(BIGINT)),
                    new ColumnMetadata("label_names", new ArrayType(VARCHAR)),
                    new ColumnMetadata("milestone_id", BIGINT),
                    new ColumnMetadata("milestone_title", VARCHAR),
                    new ColumnMetadata("active_lock_reason", VARCHAR),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("closed_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("merged_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("merge_commit_sha", VARCHAR),
                    new ColumnMetadata("assignee_id", BIGINT),
                    new ColumnMetadata("assignee_login", VARCHAR),
                    new ColumnMetadata("requested_reviewer_ids", new ArrayType(BIGINT)),
                    new ColumnMetadata("requested_reviewer_logins", new ArrayType(VARCHAR)),
                    new ColumnMetadata("head_ref", VARCHAR),
                    new ColumnMetadata("head_sha", VARCHAR),
                    new ColumnMetadata("base_ref", VARCHAR),
                    new ColumnMetadata("base_sha", VARCHAR),
                    new ColumnMetadata("author_association", VARCHAR),
                    new ColumnMetadata("draft", BOOLEAN)))
            .put("pull_commits", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("sha", VARCHAR),
                    // this column is filled in from request params, it is not returned by the api
                    new ColumnMetadata("pull_number", BIGINT),
                    new ColumnMetadata("commit_message", VARCHAR),
                    new ColumnMetadata("commit_tree_sha", VARCHAR),
                    new ColumnMetadata("commit_comments_count", BIGINT),
                    new ColumnMetadata("commit_verified", BOOLEAN),
                    new ColumnMetadata("commit_verification_reason", VARCHAR),
                    new ColumnMetadata("author_name", VARCHAR),
                    new ColumnMetadata("author_email", VARCHAR),
                    new ColumnMetadata("author_date", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("author_id", BIGINT),
                    new ColumnMetadata("author_login", VARCHAR),
                    new ColumnMetadata("committer_name", VARCHAR),
                    new ColumnMetadata("committer_email", VARCHAR),
                    new ColumnMetadata("committer_date", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("committer_id", BIGINT),
                    new ColumnMetadata("committer_login", VARCHAR),
                    new ColumnMetadata("parent_shas", new ArrayType(VARCHAR))))
            .put("reviews", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("id", BIGINT),
                    // this column is filled in from request params, it is not returned by the api
                    new ColumnMetadata("pull_number", BIGINT),
                    new ColumnMetadata("user_id", BIGINT),
                    new ColumnMetadata("user_login", VARCHAR),
                    new ColumnMetadata("body", VARCHAR),
                    new ColumnMetadata("state", VARCHAR),
                    new ColumnMetadata("submitted_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("commit_id", VARCHAR),
                    new ColumnMetadata("author_association", VARCHAR)))
            .put("review_comments", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("pull_request_review_id", BIGINT),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("diff_hunk", VARCHAR),
                    new ColumnMetadata("path", VARCHAR),
                    new ColumnMetadata("position", BIGINT),
                    new ColumnMetadata("original_position", BIGINT),
                    new ColumnMetadata("commit_id", VARCHAR),
                    new ColumnMetadata("original_commit_id", VARCHAR),
                    new ColumnMetadata("in_reply_to_id", BIGINT),
                    new ColumnMetadata("user_id", BIGINT),
                    new ColumnMetadata("user_login", VARCHAR),
                    new ColumnMetadata("body", VARCHAR),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("author_association", VARCHAR),
                    new ColumnMetadata("start_line", BIGINT),
                    new ColumnMetadata("original_start_line", BIGINT),
                    new ColumnMetadata("start_side", VARCHAR),
                    new ColumnMetadata("line", BIGINT),
                    new ColumnMetadata("original_line", BIGINT),
                    new ColumnMetadata("side", VARCHAR)))
            .put("issues", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("number", BIGINT),
                    new ColumnMetadata("state", VARCHAR),
                    new ColumnMetadata("title", VARCHAR),
                    new ColumnMetadata("body", VARCHAR),
                    new ColumnMetadata("user_id", BIGINT),
                    new ColumnMetadata("user_login", VARCHAR),
                    new ColumnMetadata("label_ids", new ArrayType(BIGINT)),
                    new ColumnMetadata("label_names", new ArrayType(VARCHAR)),
                    new ColumnMetadata("assignee_id", BIGINT),
                    new ColumnMetadata("assignee_login", VARCHAR),
                    new ColumnMetadata("milestone_id", BIGINT),
                    new ColumnMetadata("milestone_title", VARCHAR),
                    new ColumnMetadata("locked", BOOLEAN),
                    new ColumnMetadata("active_lock_reason", VARCHAR),
                    new ColumnMetadata("comments", BIGINT),
                    new ColumnMetadata("closed_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("author_association", VARCHAR)))
            .put("issue_comments", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("body", VARCHAR),
                    new ColumnMetadata("user_id", BIGINT),
                    new ColumnMetadata("user_login", VARCHAR),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("author_association", VARCHAR)))
            .put("runs", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("name", VARCHAR),
                    new ColumnMetadata("node_id", VARCHAR),
                    new ColumnMetadata("head_branch", VARCHAR),
                    new ColumnMetadata("head_sha", VARCHAR),
                    new ColumnMetadata("run_number", BIGINT),
                    new ColumnMetadata("event", VARCHAR),
                    new ColumnMetadata("status", VARCHAR),
                    new ColumnMetadata("conclusion", VARCHAR),
                    new ColumnMetadata("workflow_id", BIGINT),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3))))
            .put("jobs", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("run_id", BIGINT),
                    new ColumnMetadata("node_id", VARCHAR),
                    new ColumnMetadata("head_sha", VARCHAR),
                    new ColumnMetadata("status", VARCHAR),
                    new ColumnMetadata("conclusion", VARCHAR),
                    new ColumnMetadata("started_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("completed_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("name", VARCHAR)))
            .put("steps", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("run_id", BIGINT),
                    new ColumnMetadata("job_id", BIGINT),
                    new ColumnMetadata("name", VARCHAR),
                    new ColumnMetadata("status", VARCHAR),
                    new ColumnMetadata("conclusion", VARCHAR),
                    new ColumnMetadata("number", BIGINT),
                    new ColumnMetadata("started_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("completed_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3))))
            .put("artifacts", ImmutableList.of(
                    new ColumnMetadata("owner", VARCHAR),
                    new ColumnMetadata("repo", VARCHAR),
                    new ColumnMetadata("run_id", BIGINT),
                    new ColumnMetadata("id", BIGINT),
                    new ColumnMetadata("size_in_bytes", BIGINT),
                    new ColumnMetadata("name", VARCHAR),
                    new ColumnMetadata("url", VARCHAR),
                    new ColumnMetadata("archive_download_url", VARCHAR),
                    new ColumnMetadata("expired", BOOLEAN),
                    new ColumnMetadata("created_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("expires_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("updated_at", TimestampWithTimeZoneType.createTimestampWithTimeZoneType(3)),
                    new ColumnMetadata("filename", VARCHAR),
                    new ColumnMetadata("path", VARCHAR),
                    new ColumnMetadata("mimetype", VARCHAR),
                    new ColumnMetadata("file_size_in_bytes", BIGINT),
                    new ColumnMetadata("part_number", INTEGER),
                    new ColumnMetadata("contents", VARBINARY)))
            .build();

    // TODO add tests that would verify this using getSqlType(), print the expected string so its easy to copy&paste
    // TODO consider moving to a separate class
    public static final String ORG_ROW_TYPE = "row(" +
            "login varchar, " +
            "id bigint, " +
            "description varchar, " +
            "name varchar, " +
            "company varchar, " +
            "blog varchar, " +
            "location varchar, " +
            "email varchar, " +
            "twitter_username varchar, " +
            "is_verified boolean, " +
            "has_organization_projects boolean, " +
            "has_repository_projects boolean, " +
            "public_repos bigint, " +
            "public_gists bigint, " +
            "followers bigint, " +
            "following bigint, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone, " +
            "type varchar, " +
            "total_private_repos bigint, " +
            "owned_private_repos bigint, " +
            "private_gists bigint, " +
            "disk_usage bigint, " +
            "collaborators bigint, " +
            "billing_email varchar, " +
            "default_repository_permission varchar, " +
            "members_can_create_repositories boolean, " +
            "two_factor_requirement_enabled boolean, " +
            "members_allowed_repository_creation_type varchar, " +
            "members_can_create_public_repositories boolean, " +
            "members_can_create_private_repositories boolean, " +
            "members_can_create_internal_repositories boolean, " +
            "members_can_create_pages boolean" +
            ")";

    public static final String ORGS_TABLE_TYPE = "array(" + ORG_ROW_TYPE + ")";

    public static final String USER_ROW_TYPE = "row(" +
            "login varchar, " +
            "id bigint, " +
            "avatar_url varchar, " +
            "gravatar_id varchar, " +
            "type varchar, " +
            "site_admin boolean, " +
            "name varchar, " +
            "company varchar, " +
            "blog varchar, " +
            "location varchar, " +
            "email varchar, " +
            "hireable boolean, " +
            "bio varchar, " +
            "twitter_username varchar, " +
            "public_repos bigint, " +
            "public_gists bigint, " +
            "followers bigint, " +
            "following bigint, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone" +
            ")";

    public static final String USERS_TABLE_TYPE = "array(" + USER_ROW_TYPE + ")";

    public static final String REPOS_TABLE_TYPE = "array(row(" +
            "id bigint, " +
            "name varchar, " +
            "full_name varchar, " +
            "owner_id bigint, " +
            "owner_login varchar, " +
            "private boolean, " +
            "description varchar, " +
            "fork boolean, " +
            "homepage varchar, " +
            "url varchar, " +
            "forks_count bigint, " +
            "stargazers_count bigint, " +
            "watchers_count bigint, " +
            "size bigint, " +
            "default_branch varchar, " +
            "open_issues_count bigint, " +
            "is_template boolean, " +
            "topics array(varchar), " +
            "has_issues boolean, " +
            "has_projects boolean, " +
            "has_wiki boolean, " +
            "has_pages boolean, " +
            "has_downloads boolean, " +
            "archived boolean, " +
            "disabled boolean, " +
            "visibility varchar, " +
            "pushed_at timestamp(3) with time zone, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone, " +
            "permissions map(varchar, boolean)" +
            "))";

    public static final String PULLS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "id bigint, " +
            "number bigint, " +
            "state varchar, " +
            "locked boolean, " +
            "title varchar, " +
            "user_id bigint, " +
            "user_login varchar, " +
            "body varchar, " +
            "label_ids array(bigint), " +
            "label_names array(varchar), " +
            "milestone_id bigint, " +
            "milestone_title varchar, " +
            "active_lock_reason varchar, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone, " +
            "closed_at timestamp(3) with time zone, " +
            "merged_at timestamp(3) with time zone, " +
            "merge_commit_sha varchar, " +
            "assignee_id bigint, " +
            "assignee_login varchar, " +
            "requested_reviewer_ids array(bigint), " +
            "requested_reviewer_logins array(varchar), " +
            "head_ref varchar, " +
            "head_sha varchar, " +
            "base_ref varchar, " +
            "base_sha varchar, " +
            "author_association varchar, " +
            "draft boolean" +
            "))";

    public static final String PULL_COMMITS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "sha varchar, " +
            "pull_number bigint, " +
            "commit_message varchar, " +
            "commit_tree_sha varchar, " +
            "commit_comments_count bigint, " +
            "commit_verified boolean, " +
            "commit_verification_reason varchar, " +
            "author_name varchar, " +
            "author_email varchar, " +
            "author_date timestamp(3) with time zone, " +
            "author_id bigint, " +
            "author_login varchar, " +
            "committer_name varchar, " +
            "committer_email varchar, " +
            "committer_date timestamp(3) with time zone, " +
            "committer_id bigint, " +
            "committer_login varchar, " +
            "parent_shas array(varchar)" +
            "))";

    public static final String REVIEWS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "id bigint, " +
            "pull_number bigint, " +
            "user_id bigint, " +
            "user_login varchar, " +
            "body varchar, " +
            "state varchar, " +
            "submitted_at timestamp(3) with time zone, " +
            "commit_id varchar, " +
            "author_association varchar" +
            "))";

    public static final String REVIEW_COMMENTS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "pull_request_review_id bigint, " +
            "id bigint, " +
            "diff_hunk varchar, " +
            "path varchar, " +
            "position bigint, " +
            "original_position bigint, " +
            "commit_id varchar, " +
            "original_commit_id varchar, " +
            "in_reply_to_id bigint, " +
            "user_id bigint, " +
            "user_login varchar, " +
            "body varchar, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone, " +
            "author_association varchar, " +
            "start_line bigint, " +
            "original_start_line bigint, " +
            "start_side varchar, " +
            "line bigint, " +
            "original_line bigint, " +
            "side varchar" +
            "))";

    public static final String ISSUES_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "id bigint, " +
            "number bigint, " +
            "state varchar, " +
            "title varchar, " +
            "body varchar, " +
            "user_id bigint, " +
            "user_login varchar, " +
            "label_ids array(bigint), " +
            "label_names array(varchar), " +
            "assignee_id bigint, " +
            "assignee_login varchar, " +
            "milestone_id bigint, " +
            "milestone_title varchar, " +
            "locked boolean, " +
            "active_lock_reason varchar, " +
            "comments bigint, " +
            "closed_at timestamp(3) with time zone, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone, " +
            "author_association varchar" +
            "))";

    public static final String ISSUE_COMMENTS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "id bigint, " +
            "body varchar, " +
            "user_id bigint, " +
            "user_login varchar, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone, " +
            "author_association varchar" +
            "))";

    public static final String RUNS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "id bigint, " +
            "name varchar, " +
            "node_id varchar, " +
            "head_branch varchar, " +
            "head_sha varchar, " +
            "run_number bigint, " +
            "event varchar, " +
            "status varchar, " +
            "conclusion varchar, " +
            "workflow_id bigint, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone" +
            "))";

    public static final String JOBS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "id bigint, " +
            "run_id bigint, " +
            "node_id varchar, " +
            "head_sha varchar, " +
            "status varchar, " +
            "conclusion varchar, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone, " +
            "name varchar" +
            "))";

    public static final String STEPS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "run_id bigint, " +
            "job_id bigint, " +
            "name varchar, " +
            "status varchar, " +
            "conclusion varchar, " +
            "number bigint, " +
            "created_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone" +
            "))";

    public static final String ARTIFACTS_TABLE_TYPE = "array(row(" +
            "owner varchar, " +
            "repo varchar, " +
            "run_id bigint, " +
            "id bigint, " +
            "size_in_bytes bigint, " +
            "name varchar, " +
            "url varchar, " +
            "archive_download_url varchar, " +
            "expired boolean, " +
            "created_at timestamp(3) with time zone, " +
            "expires_at timestamp(3) with time zone, " +
            "updated_at timestamp(3) with time zone, " +
            "filename varchar, " +
            "path varchar, " +
            "mimetype varchar, " +
            "file_size_in_bytes bigint, " +
            "part_number int, " +
            "contents varbinary" +
            "))";

    private final Map<String, Map<String, ColumnHandle>> columnHandles;

    private final Map<String, ? extends FilterApplier> filterAppliers = new ImmutableMap.Builder<String, FilterApplier>()
            .put("pulls", new PullFilter())
            .put("pull_commits", new PullCommitFilter())
            .put("reviews", new ReviewFilter())
            .put("review_comments", new ReviewCommentFilter())
            .put("issues", new IssueFilter())
            .put("issue_comments", new IssueCommentFilter())
            .put("runs", new RunFilter())
            .put("jobs", new JobFilter())
            .put("steps", new StepFilter())
            .put("artifacts", new ArtifactFilter())
            .put("orgs", new OrgFilter())
            .put("users", new UserFilter())
            .put("repos", new RepoFilter())
            .build();

    public GithubRest(String token)
    {
        GithubRest.token = token;

        columnHandles = columns.keySet()
                .stream()
                .collect(Collectors.toMap(
                        tableName -> tableName,
                        tableName -> columns.get(tableName)
                                .stream()
                                .collect(toMap(
                                        ColumnMetadata::getName,
                                        column -> new RestColumnHandle(column.getName(), column.getType())))));
    }

    public static GithubService getService()
    {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();

        // TODO make configurable? https://github.com/nineinchnick/trino-rest/issues/22
        Path cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "trino-rest-cache");
        clientBuilder.cache(new Cache(cacheDir.toFile(), 10 * 1024 * 1024));

        if (getLogLevel().intValue() <= Level.FINE.intValue()) {
            HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor();
            interceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
            clientBuilder.addInterceptor(interceptor);
        }

        return new Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .client(clientBuilder.build())
                .addConverterFactory(JacksonConverterFactory.create(
                        new ObjectMapper()
                                .registerModule(new Jdk8Module())
                                .registerModule(new JavaTimeModule())))
                .build()
                .create(GithubService.class);
    }

    public static String getToken()
    {
        return token;
    }

    public static <T> void checkServiceResponse(Response<T> response)
    {
        if (response.isSuccessful()) {
            return;
        }
        ResponseBody error = response.errorBody();
        String message = "Unable to read: ";
        if (error != null) {
            try {
                // TODO unserialize the JSON in error: https://github.com/nineinchnick/trino-rest/issues/33
                message += error.string();
            }
            catch (IOException e) {
                // pass
            }
        }
        throw new TrinoException(GENERIC_INTERNAL_ERROR, message);
    }

    private static Level getLogLevel()
    {
        String loggerName = BaseFunction.class.getName();
        Logger logger = Logger.getLogger(loggerName);
        Level level = logger.getLevel();
        while (level == null) {
            Logger parent = logger.getParent();
            if (parent == null) {
                return Level.OFF;
            }
            level = parent.getLevel();
        }
        return level;
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(SchemaTableName schemaTableName)
    {
        return new ConnectorTableMetadata(
                schemaTableName,
                columns.get(schemaTableName.getTableName()));
    }

    @Override
    public List<String> listSchemas()
    {
        return ImmutableList.of(SCHEMA_NAME);
    }

    @Override
    public List<SchemaTableName> listTables(String schema)
    {
        return columns
                .keySet()
                .stream()
                .map(name -> new SchemaTableName(SCHEMA_NAME, name))
                .collect(toList());
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(SchemaTablePrefix schemaTablePrefix)
    {
        return columns.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        e -> new SchemaTableName(schemaTablePrefix.getSchema().orElse(""), e.getKey()),
                        Map.Entry::getValue));
    }

    @Override
    public Collection<? extends List<?>> getRows(RestTableHandle table)
    {
        switch (table.getSchemaTableName().getTableName()) {
            case "orgs":
                return getOrgs(table);
            case "users":
                return getUsers(table);
            case "repos":
                return getRepos(table);
            case "pulls":
                return getPulls(table);
            case "pull_commits":
                return getPullCommits(table);
            case "reviews":
                return getReviews(table);
            case "review_comments":
                return getReviewComments(table);
            case "issues":
                return getIssues(table);
            case "issue_comments":
                return getIssueComments(table);
            case "runs":
                return getRuns(table);
            case "jobs":
                return getJobs(table);
            case "steps":
                return getSteps(table);
            case "artifacts":
                return getArtifacts(table);
        }
        return null;
    }

    private Collection<? extends List<?>> getOrgs(RestTableHandle table)
    {
        if (table.getLimit() == 0) {
            return ImmutableList.of();
        }
        String tableName = table.getSchemaTableName().getTableName();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String login = (String) filter.getFilter((RestColumnHandle) columns.get("login"), table.getConstraint());
        return getRow(() -> service.getOrg("Bearer " + token, login), Organization::toRow);
    }

    private Collection<? extends List<?>> getUsers(RestTableHandle table)
    {
        if (table.getLimit() == 0) {
            return ImmutableList.of();
        }
        String tableName = table.getSchemaTableName().getTableName();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String login = (String) filter.getFilter((RestColumnHandle) columns.get("login"), table.getConstraint());
        return getRow(() -> service.getUser("Bearer " + token, login), User::toRow);
    }

    private Collection<? extends List<?>> getRepos(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner_login"), table.getConstraint());
        return getRowsFromPages(
                page -> service.listUserRepos("Bearer " + token, owner, PER_PAGE, page, "full_name"),
                Repository::toRow,
                table.getLimit());
    }

    private Collection<? extends List<?>> getPulls(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        // TODO allow filtering by state (many, comma separated, or all), requires https://github.com/nineinchnick/trino-rest/issues/30
        return getRowsFromPages(
                page -> service.listPulls("Bearer " + token, owner, repo, PER_PAGE, page, "created", "all"),
                item -> {
                    item.setOwner(owner);
                    item.setRepo(repo);
                    return item.toRow();
                },
                table.getLimit());
    }

    private Collection<? extends List<?>> getPullCommits(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        long pullNumber = (long) filter.getFilter((RestColumnHandle) columns.get("pull_number"), constraint);
        // TODO allow filtering by state (many, comma separated, or all), requires https://github.com/nineinchnick/trino-rest/issues/30
        return getRowsFromPages(
                page -> service.listPullCommits("Bearer " + token, owner, repo, pullNumber, PER_PAGE, page),
                item -> {
                    item.setOwner(owner);
                    item.setRepo(repo);
                    item.setPullNumber(pullNumber);
                    return item.toRow();
                },
                table.getLimit());
    }

    private Collection<? extends List<?>> getReviews(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        long pullNumber = (long) filter.getFilter((RestColumnHandle) columns.get("pull_number"), constraint);
        return getRowsFromPages(
                page -> service.listPullReviews("Bearer " + token, owner, repo, pullNumber, PER_PAGE, page),
                item -> {
                    item.setOwner(owner);
                    item.setRepo(repo);
                    return item.toRow();
                },
                table.getLimit());
    }

    private Collection<? extends List<?>> getReviewComments(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        String since = (String) filter.getFilter((RestColumnHandle) columns.get("updated_at"), constraint);
        // TODO allow filtering by pull number, this would require using a different endpoint
        return getRowsFromPages(
                page -> service.listReviewComments("Bearer " + token, owner, repo, PER_PAGE, page, since),
                item -> {
                    item.setOwner(owner);
                    item.setRepo(repo);
                    return item.toRow();
                },
                table.getLimit());
    }

    private Collection<? extends List<?>> getIssues(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        String since = (String) filter.getFilter((RestColumnHandle) columns.get("updated_at"), constraint);
        return getRowsFromPages(
                page -> service.listIssues("Bearer " + token, owner, repo, PER_PAGE, page, since),
                item -> {
                    item.setOwner(owner);
                    item.setRepo(repo);
                    return item.toRow();
                },
                table.getLimit());
    }

    private Collection<? extends List<?>> getIssueComments(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        String since = (String) filter.getFilter((RestColumnHandle) columns.get("updated_at"), constraint);
        return getRowsFromPages(
                page -> service.listIssueComments("Bearer " + token, owner, repo, PER_PAGE, page, since),
                item -> {
                    item.setOwner(owner);
                    item.setRepo(repo);
                    return item.toRow();
                },
                table.getLimit());
    }

    private Collection<? extends List<?>> getRuns(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        return getRowsFromPagesEnvelope(
                page -> service.listRuns("Bearer " + token, owner, repo, PER_PAGE, page),
                item -> {
                    item.setOwner(owner);
                    item.setRepo(repo);
                    return Stream.of(item.toRow());
                },
                table.getLimit());
    }

    private Collection<? extends List<?>> getJobs(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        Long runId = (Long) filter.getFilter((RestColumnHandle) columns.get("run_id"), constraint);
        if (runId == null) {
            throw new TrinoException(INVALID_ROW_FILTER, "Missing required constraint for run_id");
        }
        // TODO this needs to allow pushing down multiple run_id values and make a separate request for each: https://github.com/nineinchnick/trino-rest/issues/30
        return getRowsFromPagesEnvelope(
                page -> service.listRunJobs("Bearer " + token, owner, repo, runId, "all", PER_PAGE, page),
                item -> {
                    item.setOwner(owner);
                    item.setRepo(repo);
                    return Stream.of(item.toRow());
                },
                table.getLimit());
    }

    private Collection<? extends List<?>> getSteps(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        Long runId = (Long) filter.getFilter((RestColumnHandle) columns.get("run_id"), constraint);
        if (runId == null) {
            throw new TrinoException(INVALID_ROW_FILTER, "Missing required constraint for run_id");
        }

        ImmutableList.Builder<Job> jobs = new ImmutableList.Builder<>();

        int page = 1;
        while (true) {
            Response<JobsList> response;
            try {
                response = service.listRunJobs("Bearer " + token, owner, repo, runId, "all", PER_PAGE, page++).execute();
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
            if (response.code() == HTTP_NOT_FOUND) {
                break;
            }
            checkServiceResponse(response);
            List<Job> items = requireNonNull(response.body()).getItems();
            if (items.size() == 0) {
                break;
            }
            items.forEach(i -> i.setOwner(owner));
            items.forEach(i -> i.setRepo(repo));
            jobs.addAll(items);
            if (items.size() < PER_PAGE) {
                break;
            }
        }

        ImmutableList.Builder<List<?>> result = new ImmutableList.Builder<>();
        int resultSize = 0;
        for (Job job : jobs.build()) {
            List<List<?>> steps = job.getSteps().stream().map(Step::toRow).collect(toList());
            int stepsToUse = Math.max(0, table.getLimit() - resultSize);
            if (steps.size() > stepsToUse) {
                steps = steps.subList(0, stepsToUse);
            }
            else {
                stepsToUse = steps.size();
            }
            result.addAll(steps);
            resultSize += stepsToUse;
        }
        return result.build();
    }

    private Collection<? extends List<?>> getArtifacts(RestTableHandle table)
    {
        String tableName = table.getSchemaTableName().getTableName();
        TupleDomain<ColumnHandle> constraint = table.getConstraint();
        Map<String, ColumnHandle> columns = columnHandles.get(tableName);
        FilterApplier filter = filterAppliers.get(tableName);

        String owner = (String) filter.getFilter((RestColumnHandle) columns.get("owner"), constraint);
        String repo = (String) filter.getFilter((RestColumnHandle) columns.get("repo"), constraint);
        Long runId = (Long) filter.getFilter((RestColumnHandle) columns.get("run_id"), constraint);

        IntFunction<Call<ArtifactsList>> fetcher = page -> service.listArtifacts("Bearer " + token, owner, repo, PER_PAGE, page);
        if (runId != null) {
            fetcher = page -> service.listRunArtifacts("Bearer " + token, owner, repo, runId, PER_PAGE, page);
        }
        else {
            log.warning(format("Missing filter on run_id, will try to fetch all artifacts for %s/%s", owner, repo));
        }
        // TODO this needs to allow pushing down multiple run_id values and make a separate request for each: https://github.com/nineinchnick/trino-rest/issues/30
        return getRowsFromPagesEnvelope(
                fetcher,
                item -> {
                    Stream.Builder<List<?>> result = Stream.builder();
                    item.setOwner(owner);
                    item.setRepo(repo);
                    if (runId != null) {
                        item.setRunId(runId);
                    }
                    try {
                        for (Artifact artifact : download(service, token, item)) {
                            result.add(artifact.toRow());
                        }
                    }
                    catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return result.build();
                },
                table.getLimit());
    }

    private <T> Collection<? extends List<?>> getRow(Supplier<Call<T>> fetcher, Function<T, List<?>> mapper)
    {
        Response<T> response;
        try {
            response = fetcher.get().execute();
        }
        catch (IOException e) {
            throw Throwables.propagate(e);
        }
        if (response.code() == HTTP_NOT_FOUND) {
            return ImmutableList.of();
        }
        checkServiceResponse(response);
        return ImmutableList.of(mapper.apply(response.body()));
    }

    // TODO this abomination should be in a base class implementing a cursor
    private <T> Collection<? extends List<?>> getRowsFromPages(IntFunction<Call<List<T>>> fetcher, Function<T, List<?>> mapper, int limit)
    {
        ImmutableList.Builder<List<?>> result = new ImmutableList.Builder<>();
        int resultSize = 0;

        int page = 1;
        while (true) {
            Response<List<T>> response;
            try {
                response = fetcher.apply(page++).execute();
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
            if (response.code() == HTTP_NOT_FOUND) {
                break;
            }
            checkServiceResponse(response);
            List<T> items = requireNonNull(response.body());
            if (items.size() == 0) {
                break;
            }
            int itemsToUse = Math.min(
                    Math.max(0, limit - resultSize),
                    items.size());
            List<List<?>> rows = items.subList(0, itemsToUse).stream().map(mapper).collect(toList());

            result.addAll(rows);
            resultSize += itemsToUse;
            if (resultSize >= limit || items.size() < PER_PAGE) {
                break;
            }
        }

        return result.build();
    }

    // TODO this abomination is even worse
    private <T, E extends Envelope<T>> Collection<? extends List<?>> getRowsFromPagesEnvelope(IntFunction<Call<E>> fetcher, Function<T, Stream<List<?>>> mapper, int limit)
    {
        ImmutableList.Builder<List<?>> result = new ImmutableList.Builder<>();
        int resultSize = 0;

        int page = 1;
        while (true) {
            Response<E> response;
            try {
                response = fetcher.apply(page++).execute();
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
            if (response.code() == HTTP_NOT_FOUND) {
                break;
            }
            checkServiceResponse(response);
            E envelope = requireNonNull(response.body());
            List<T> items = envelope.getItems();
            if (items.size() == 0) {
                break;
            }
            int itemsToUse = Math.min(
                    Math.max(0, limit - resultSize),
                    items.size());
            List<List<?>> rows = items.subList(0, itemsToUse).stream().flatMap(mapper).collect(toList());

            // mapper can produce 1 or more rows per item, so subList them again
            result.addAll(rows.subList(0, itemsToUse));
            resultSize += itemsToUse;
            if (resultSize >= limit || resultSize >= envelope.getTotalCount()) {
                break;
            }
        }

        return result.build();
    }

    @Override
    public Consumer<List> createRowSink(SchemaTableName schemaTableName)
    {
        throw new IllegalStateException("This connector does not support write");
    }

    public static String getSqlType(String tableName)
    {
        return columns.get(tableName)
                .stream()
                .map(column -> column.getName() + " " + column.getType().getDisplayName())
                .collect(Collectors.joining(", ", "ARRAY(ROW(", "))"));
    }

    public static RowType getRowType(String tableName)
    {
        List<RowType.Field> fields = GithubRest.columns.get(tableName)
                .stream()
                .map(columnMetadata -> RowType.field(
                        columnMetadata.getName(),
                        columnMetadata.getType()))
                .collect(Collectors.toList());
        return RowType.from(fields);
    }

    @Override
    public Optional<LimitApplicationResult<ConnectorTableHandle>> applyLimit(
            ConnectorSession session,
            ConnectorTableHandle table,
            long limit)
    {
        RestTableHandle restTable = (RestTableHandle) table;
        return Optional.of(new LimitApplicationResult<>(
                new RestTableHandle(
                        restTable.getSchemaTableName(),
                        restTable.getConstraint(),
                        (int) Math.min(limit, Integer.MAX_VALUE)),
                true));
    }

    @Override
    public Optional<TopNApplicationResult<ConnectorTableHandle>> applyTopN(
            ConnectorSession session,
            ConnectorTableHandle handle,
            long topNCount,
            List<SortItem> sortItems,
            Map<String, ColumnHandle> assignments)
    {
        // TODO this can be applied, if sortItems match either default sorting, or supported sorting
        // new JdbcSortItem(((JdbcColumnHandle) assignments.get(sortItem.getName())), sortItem.getSortOrder())
        /*
        orgs: created_at asc
        repos: full_name asc, created_at desc, updated_at desc, pushed_at desc
        pulls: created desc, updated asc
        pull_commits: created asc
        reviews: created asc
        review_comments: created asc
        issues: created desc, updated desc, comments desc
        issue_comments: created asc
        runs: created desc
        jobs: started_at asc
        steps: job_id asc AND started_at desc
        artifacts: created desc
         */
        return Optional.empty();
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(
            ConnectorSession session,
            ConnectorTableHandle table,
            Constraint constraint)
    {
        RestTableHandle restTable = (RestTableHandle) table;
        String tableName = restTable.getSchemaTableName().getTableName();

        FilterApplier filterApplier = filterAppliers.get(tableName);
        if (filterApplier == null) {
            return Optional.empty();
        }
        return filterApplier.applyFilter(
                restTable,
                columnHandles.get(tableName),
                filterApplier.getSupportedFilters(),
                constraint.getSummary());
    }
}
