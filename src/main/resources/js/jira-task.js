(function () {
    'use strict';

    var REST_BASE = '/rest/jira-task/1.0';

    // 同一页面上的多个宏共享项目/优先级列表，避免重复请求
    var shared = { projects: null, priorities: null };

    function esc(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function text($macro, key, fallback) {
        var value = $macro.attr('data-i18n-' + key);
        return value == null || value === '' ? fallback : value;
    }

    function api($macro, path, options) {
        options = options || {};
        var settings = {
            url: AJS.contextPath() + REST_BASE + path,
            type: options.type || 'GET',
            data: options.data,
            dataType: 'json',
            cache: false,
            headers: { 'X-Atlassian-Token': 'no-check' }
        };
        if (settings.type === 'POST') {
            settings.contentType = 'application/json';
        }
        return $.ajax(settings);
    }

    /**
     * 共享请求：成功后缓存结果供其他宏复用，失败时清空缓存以便下次重试。
     */
    function sharedRequest(name, $macro, path) {
        if (!shared[name]) {
            shared[name] = api($macro, path);
            shared[name].fail(function () {
                shared[name] = null;
            });
        }
        return shared[name];
    }

    function errorText(xhr, fallback) {
        if (xhr && xhr.responseJSON && xhr.responseJSON.error) {
            return xhr.responseJSON.error;
        }
        if (xhr && xhr.responseText) {
            try {
                var parsed = JSON.parse(xhr.responseText);
                if (parsed && parsed.error) {
                    return parsed.error;
                }
            } catch (ignored) {
                // 忽略非 JSON 响应
            }
        }
        return fallback;
    }

    function option(value, label) {
        return '<option value="' + esc(value) + '">' + esc(label) + '</option>';
    }

    function relatedItem(issue, t) {
        var html = '<li>'
            + '<a href="' + esc(issue.url || '#') + '" target="_blank" rel="noopener noreferrer">'
            + esc(issue.key || '') + '</a>'
            + '<span class="jt-related-summary">' + esc(issue.summary || '') + '</span>';
        if (issue.status) {
            html += '<span class="jt-tag">' + esc(issue.status) + '</span>';
        }
        if (issue.assignee) {
            html += '<span class="jt-muted">' + esc(issue.assignee) + '</span>';
        }
        if (issue.dueDate) {
            html += '<span class="jt-muted">'
                + esc(t('due', 'Due {0}').replace('{0}', issue.dueDate))
                + '</span>';
        }
        return html + '</li>';
    }

    /**
     * @param created 刚创建的任务，Jira 搜索索引可能还没收录，先直接补到列表里
     */
    function loadRelated($macro, t, created) {
        var pageId = $macro.attr('data-page-id');
        if (!pageId) {
            return;
        }
        api($macro, '/issues', { data: { pageId: pageId } }).done(function (data) {
            var issues = (data && data.issues) || [];
            var total = data && data.total != null ? data.total : issues.length;
            var $related = $macro.find('.jt-related');

            var known = {};
            $.each(issues, function (index, issue) {
                known[issue.key] = true;
            });
            var missing = created && created.key && !known[created.key] ? created : null;
            if (!issues.length && !missing) {
                $related.hide().empty();
                return;
            }

            var shown = issues.length + (missing ? 1 : 0);
            var count = Math.max(total, shown);
            var title = t('related-title', 'Issues created on this page ({0})').replace('{0}', count);
            var html = '<div class="jt-related-title">' + esc(title);
            if (count > shown) {
                html += ' <span class="jt-muted">'
                    + esc(t('related-truncated', 'Total {0}, showing {1}')
                        .replace('{0}', count).replace('{1}', shown))
                    + '</span>';
            }
            html += '</div><ul>';

            if (missing) {
                html += relatedItem(missing, t);
            }
            $.each(issues, function (index, issue) {
                html += relatedItem(issue, t);
            });
            html += '</ul>';
            $related.html(html).show();
        }).fail(function (xhr) {
            $macro.find('.jt-related').html('<span class="jt-muted">'
                + esc(errorText(xhr, t('error-related', 'Failed to load related issues')))
                + '</span>').show();
        });
    }

    function init($macro) {
        if ($macro.data('jt-initialized')) {
            return;
        }
        $macro.data('jt-initialized', true);

        var t = function (key, fallback) {
            return text($macro, key, fallback);
        };
        var pageId = $macro.attr('data-page-id') || '';
        var pageTitle = $macro.attr('data-page-title') || '';
        var summaryPrefix = $macro.attr('data-summary-prefix') || '';
        var defaultProject = $macro.attr('data-default-project') || '';
        var defaultIssueType = $macro.attr('data-default-issue-type') || '';
        var labels = ($macro.attr('data-labels') || '').split(',')
            .map(function (label) { return label.trim(); })
            .filter(function (label) { return label !== ''; });

        var html = ''
            + '<div class="jt-head"><span class="jt-badge">JIRA</span>'
            + '<span class="jt-title">' + esc(t('head', 'New task')) + '</span></div>'
            + '<div class="jt-alert" style="display:none"></div>'
            + '<form class="jt-form">'
            + '<div class="jt-grid">'
            + '<label><span>' + esc(t('label-project', 'Project')) + '</span>'
            + '<select class="jt-project"></select></label>'
            + '<label><span>' + esc(t('label-issue-type', 'Issue type')) + '</span>'
            + '<select class="jt-issue-type"></select></label>'
            + '<label style="grid-column:1/-1"><span>' + esc(t('label-summary', 'Summary')) + '</span>'
            + '<input type="text" class="jt-summary" placeholder="'
            + esc(t('placeholder-summary', '')) + '"></label>'
            + '<label style="grid-column:1/-1"><span>' + esc(t('label-description', 'Description')) + '</span>'
            + '<textarea class="jt-description" rows="4" placeholder="'
            + esc(t('placeholder-description', '')) + '"></textarea></label>'
            + '<label><span>' + esc(t('label-due-date', 'Due date')) + '</span>'
            + '<input type="date" class="jt-due"></label>'
            + '<label><span>' + esc(t('label-priority', 'Priority')) + '</span>'
            + '<select class="jt-priority"></select></label>'
            + '<label style="grid-column:1/-1"><span>' + esc(t('label-assignee', 'Assignee')) + '</span>'
            + '<div class="jt-row"><input type="text" class="jt-assignee" placeholder="'
            + esc(t('placeholder-assignee', '')) + '">'
            + '<button type="button" class="aui-button jt-search">'
            + esc(t('search', 'Search')) + '</button></div>'
            + '<div class="jt-users" style="display:none"></div>'
            + '<div class="jt-picked" style="display:none"><span class="jt-picked-name"></span>'
            + '<button type="button" class="aui-button jt-remove-user">'
            + esc(t('remove', 'Remove')) + '</button></div></label>'
            + '</div>'
            + '<div class="jt-actions">'
            + '<button type="submit" class="aui-button aui-button-primary jt-submit">'
            + esc(t('create', 'Create issue')) + '</button>'
            + '<span class="jt-status"></span>'
            + '</div>'
            + '</form>'
            + '<div class="jt-related" style="display:none"></div>';

        $macro.html(html);

        var $form = $macro.find('.jt-form');
        var $alert = $macro.find('.jt-alert');
        var $status = $macro.find('.jt-status');
        var $submit = $macro.find('.jt-submit');
        var $project = $macro.find('.jt-project');
        var $issueType = $macro.find('.jt-issue-type');
        var $summary = $macro.find('.jt-summary');
        var $description = $macro.find('.jt-description');
        var $due = $macro.find('.jt-due');
        var $priority = $macro.find('.jt-priority');
        var $assignee = $macro.find('.jt-assignee');
        var $users = $macro.find('.jt-users');
        var $picked = $macro.find('.jt-picked');
        var $pickedName = $macro.find('.jt-picked-name');

        var selectedUser = null;
        var typeRequest = null;
        var typeSequence = 0;

        function showError(message) {
            $alert.text(message).show();
        }

        function clearError() {
            $alert.text('').hide();
        }

        // 草稿/预览状态没有页面 ID，任务无法关联到页面，直接提示先保存
        if (!pageId) {
            showError(t('error-unsaved', 'Save the page before creating a Jira issue'));
            $submit.prop('disabled', true);
        }

        function loadProjects() {
            sharedRequest('projects', $macro, '/projects').done(function (projects) {
                var options = option('', t('select', 'Select…'));
                $.each(projects || [], function (index, project) {
                    options += option(project.key, project.name || project.key);
                });
                $project.html(options);
                if (defaultProject) {
                    $project.val(defaultProject);
                }
                loadIssueTypes($project.val());
            }).fail(function (xhr) {
                showError(errorText(xhr, t('error-projects', 'Unable to reach Jira.')));
            });
        }

        function loadIssueTypes(projectKey) {
            var sequence = ++typeSequence;
            if (typeRequest) {
                typeRequest.abort();
                typeRequest = null;
            }
            if (!projectKey) {
                $issueType.html(option('', t('select-first', 'Select a project first')));
                return;
            }

            $issueType.html(option('', t('loading', 'Loading…')));
            typeRequest = api($macro, '/issuetypes', { data: { projectKey: projectKey } });
            typeRequest.done(function (types) {
                if (sequence !== typeSequence) {
                    return;
                }
                var options = option('', t('select', 'Select…'));
                $.each(types || [], function (index, type) {
                    options += option(type.name, type.name);
                });
                $issueType.html(options);
                if (defaultIssueType) {
                    $issueType.val(defaultIssueType);
                }
            }).fail(function (xhr) {
                if (sequence !== typeSequence) {
                    return;
                }
                $issueType.html(option('', errorText(xhr, t('error-issue-types', 'Failed to load issue types'))));
            });
        }

        function loadPriorities() {
            sharedRequest('priorities', $macro, '/priorities').done(function (priorities) {
                var options = option('', t('priority-default', 'Default'));
                $.each(priorities || [], function (index, priority) {
                    options += option(priority.id, priority.name);
                });
                $priority.html(options);
            }).fail(function () {
                $priority.html(option('', t('priority-default', 'Default')));
            });
        }

        function pickUser(name, accountId, displayName) {
            selectedUser = { name: name || '', accountId: accountId || '', displayName: displayName || '' };
            $assignee.val(selectedUser.displayName);
            $pickedName.text(selectedUser.displayName);
            $picked.show();
            $users.hide().empty();
        }

        function clearUser() {
            selectedUser = null;
            $assignee.val('');
            $picked.hide();
            $users.hide().empty();
        }

        function searchUsers() {
            var query = ($assignee.val() || '').trim();
            var projectKey = $project.val() || '';
            if (!query) {
                return;
            }
            if (!projectKey) {
                $users.html('<span class="jt-muted">'
                    + esc(t('select-first', 'Select a project first')) + '</span>').show();
                return;
            }

            $users.html('<span class="jt-muted">' + esc(t('loading', 'Loading…')) + '</span>').show();
            api($macro, '/users', { data: { projectKey: projectKey, query: query } })
                .done(function (users) {
                    if (!users || !users.length) {
                        $users.html('<span class="jt-muted">' + esc(t('load-failed', 'Load failed')) + '</span>');
                        return;
                    }
                    var items = '';
                    $.each(users, function (index, user) {
                        var displayName = user.displayName || user.name || '';
                        items += '<button type="button" class="jt-user-item" data-name="'
                            + esc(user.name || '') + '" data-account-id="'
                            + esc(user.accountId || '') + '" data-display-name="'
                            + esc(displayName) + '">' + esc(displayName) + '</button>';
                    });
                    $users.html(items);
                })
                .fail(function (xhr) {
                    $users.html('<span class="jt-muted">'
                        + esc(errorText(xhr, t('error-users', 'User search failed'))) + '</span>');
                });
        }

        loadProjects();
        loadPriorities();
        loadRelated($macro, t);

        $project.on('change', function () {
            loadIssueTypes($project.val());
        });

        $macro.find('.jt-search').on('click', searchUsers);

        $assignee.on('keydown', function (event) {
            if (event.key === 'Enter') {
                event.preventDefault();
                searchUsers();
            }
        });

        $assignee.on('input', function () {
            if (selectedUser && $assignee.val() !== selectedUser.displayName) {
                selectedUser = null;
                $picked.hide();
            }
        });

        $users.on('click', '.jt-user-item', function () {
            pickUser($(this).attr('data-name'), $(this).attr('data-account-id'),
                $(this).attr('data-display-name'));
        });

        $macro.find('.jt-remove-user').on('click', clearUser);

        $form.on('submit', function (event) {
            event.preventDefault();
            clearError();
            $status.empty();

            var projectKey = $project.val() || '';
            var issueTypeName = $issueType.val() || '';
            var summary = ($summary.val() || '').trim();
            if (!projectKey || !issueTypeName || !summary) {
                showError(t('error-required', 'Project, issue type and summary are required'));
                return;
            }
            if (!pageId) {
                showError(t('error-unsaved', 'Save the page before creating a Jira issue'));
                return;
            }

            var payload = {
                pageId: pageId,
                projectKey: projectKey,
                issueTypeName: issueTypeName,
                summary: summary,
                description: ($description.val() || '').trim(),
                dueDate: $due.val() || '',
                priorityId: $priority.val() || '',
                labels: labels,
                assigneeName: selectedUser ? selectedUser.name : '',
                assigneeAccountId: selectedUser ? selectedUser.accountId : ''
            };

            $submit.prop('disabled', true).text(t('creating', 'Creating…'));
            api($macro, '/issues', { type: 'POST', data: JSON.stringify(payload) })
                .done(function (issue) {
                    var link = '<a href="' + esc(issue.url || '#') + '" target="_blank" '
                        + 'rel="noopener noreferrer">' + esc(issue.key || '') + '</a>';
                    $status.html(esc(t('created', 'Created {0}')).replace('{0}', link));
                    $summary.val(summaryPrefix ? summaryPrefix + pageTitle : '');
                    loadRelated($macro, t, {
                        key: issue.key || '',
                        url: issue.url || '',
                        summary: issue.summary || summary
                    });
                })
                .fail(function (xhr) {
                    showError(errorText(xhr, t('error-create', 'Create failed')));
                })
                .always(function () {
                    $submit.prop('disabled', false).text(t('create', 'Create issue'));
                });
        });
    }

    AJS.toInit(function () {
        $('.jira-task-macro').each(function () {
            init($(this));
        });
    });
}());
