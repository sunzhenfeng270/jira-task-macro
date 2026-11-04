(function ($) {
    'use strict';

    function api(path, options) {
        return $.ajax($.extend({
            url: AJS.contextPath() + '/rest/jira-task/1.0' + path,
            dataType: 'json',
            headers: { 'X-Atlassian-Token': 'no-check' }
        }, options));
    }

    function esc(value) {
        return $('<div/>').text(value == null ? '' : String(value)).html();
    }

    function errorText(xhr, fallback) {
        var body = xhr && xhr.responseJSON;
        return (body && body.error) || fallback;
    }

    function loadRelated($macro, pageId) {
        if (!pageId) {
            return;
        }
        api('/issues', { data: { pageId: pageId } }).done(function (issues) {
            var $related = $macro.find('.jt-related');
            if (!issues || !issues.length) {
                $related.hide().empty();
                return;
            }
            var html = '<div class="jt-related-title">本页已创建的任务（' + issues.length + '）</div><ul>';
            $.each(issues, function (i, issue) {
                html += '<li>'
                    + '<a href="' + esc(issue.url) + '" target="_blank" rel="noreferrer">' + esc(issue.key) + '</a>'
                    + '<span class="jt-related-summary">' + esc(issue.summary) + '</span>'
                    + (issue.status ? '<span class="jt-tag">' + esc(issue.status) + '</span>' : '')
                    + (issue.assignee ? '<span class="jt-muted">' + esc(issue.assignee) + '</span>' : '')
                    + (issue.dueDate ? '<span class="jt-muted">截止 ' + esc(issue.dueDate) + '</span>' : '')
                    + '</li>';
            });
            $related.html(html + '</ul>').show();
        });
    }

    function init($macro) {
        var pageId = $macro.attr('data-page-id') || '';
        var pageTitle = $macro.attr('data-page-title') || '';
        var defaults = {
            projectKey: $macro.attr('data-default-project') || '',
            issueTypeName: $macro.attr('data-default-issue-type') || '',
            summaryPrefix: $macro.attr('data-summary-prefix') || '',
            labels: $macro.attr('data-labels') || ''
        };
        var assignee = null;

        var html = ''
            + '<div class="jt-head"><span class="jt-badge">Jira</span><span class="jt-title">新建任务</span></div>'
            + '<div class="jt-alert" style="display:none"></div>'
            + '<div class="jt-form">'
            + '  <div class="jt-grid">'
            + '    <label>项目<select class="jt-project"><option value="">加载中…</option></select></label>'
            + '    <label>任务类型<select class="jt-type"><option value="">加载中…</option></select></label>'
            + '  </div>'
            + '  <label>标题<input type="text" class="jt-summary" placeholder="例如：【会议行动项】补充登录页埋点" /></label>'
            + '  <label>描述<textarea rows="4" class="jt-description" placeholder="背景、验收标准、会议结论…"></textarea></label>'
            + '  <div class="jt-grid">'
            + '    <label>截止日期<input type="date" class="jt-due" /></label>'
            + '    <label>优先级<select class="jt-priority"><option value="">默认</option></select></label>'
            + '  </div>'
            + '  <div class="jt-field"><span class="jt-field-label">经办人</span><div class="jt-assignee-view"></div></div>'
            + '  <div class="jt-actions">'
            + '    <button type="button" class="aui-button aui-button-primary jt-create">创建任务</button>'
            + '    <span class="jt-status"></span>'
            + '  </div>'
            + '</div>'
            + '<div class="jt-related" style="display:none"></div>';

        $macro.html(html);

        var $project = $macro.find('.jt-project');
        var $type = $macro.find('.jt-type');
        var $summary = $macro.find('.jt-summary');
        var $description = $macro.find('.jt-description');
        var $due = $macro.find('.jt-due');
        var $priority = $macro.find('.jt-priority');
        var $assigneeView = $macro.find('.jt-assignee-view');
        var $create = $macro.find('.jt-create');
        var $status = $macro.find('.jt-status');
        var $alert = $macro.find('.jt-alert');

        if (defaults.summaryPrefix) {
            $summary.val(defaults.summaryPrefix + pageTitle);
        }

        function renderAssignee() {
            if (assignee) {
                $assigneeView.html('<span class="jt-picked">' + esc(assignee.displayName)
                    + ' <a href="#" class="jt-remove-assignee">移除</a></span>');
            } else {
                $assigneeView.html('<div class="jt-row">'
                    + '<input type="text" class="jt-user-query" placeholder="姓名或邮箱，回车搜索" />'
                    + '<button type="button" class="aui-button jt-user-search">搜索</button>'
                    + '</div><div class="jt-users"></div>');
            }
        }

        renderAssignee();

        $assigneeView.on('click', '.jt-remove-assignee', function (e) {
            e.preventDefault();
            assignee = null;
            renderAssignee();
        });

        function searchUsers() {
            var query = $assigneeView.find('.jt-user-query').val();
            if (!query) {
                return;
            }
            api('/users', { data: { projectKey: $project.val(), query: query } })
                .done(function (users) {
                    var $list = $assigneeView.find('.jt-users').empty();
                    $.each(users, function (i, user) {
                        $('<button type="button" class="aui-button aui-button-link jt-user-item"></button>')
                            .text(user.displayName + (user.email ? '（' + user.email + '）' : ''))
                            .data('user', user)
                            .appendTo($list);
                    });
                })
                .fail(function (xhr) {
                    $alert.text(errorText(xhr, '用户搜索失败')).show();
                });
        }

        $assigneeView.on('click', '.jt-user-search', searchUsers);
        $assigneeView.on('keydown', '.jt-user-query', function (e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                searchUsers();
            }
        });
        $assigneeView.on('click', '.jt-user-item', function () {
            assignee = $(this).data('user');
            renderAssignee();
        });

        function loadTypes(projectKey) {
            $type.empty().append('<option value="">加载中…</option>');
            if (!projectKey) {
                $type.empty().append('<option value="">请先选择项目</option>');
                return;
            }
            api('/issuetypes', { data: { projectKey: projectKey } })
                .done(function (types) {
                    $type.empty().append('<option value="">请选择</option>');
                    $.each(types, function (i, type) {
                        $('<option/>').val(type.name).text(type.name).appendTo($type);
                    });
                    var preferred = defaults.issueTypeName;
                    var matched = false;
                    if (preferred) {
                        $.each(types, function (i, type) {
                            if (type.name === preferred) {
                                matched = true;
                            }
                        });
                    }
                    $type.val(matched ? preferred : (types.length ? types[0].name : ''));
                })
                .fail(function (xhr) {
                    $type.empty().append('<option value="">加载失败</option>');
                    $alert.text(errorText(xhr, '任务类型加载失败')).show();
                });
        }

        $project.on('change', function () {
            loadTypes($project.val());
        });

        api('/projects').done(function (projects) {
            $project.empty().append('<option value="">请选择</option>');
            var matched = false;
            $.each(projects, function (i, project) {
                $('<option/>').val(project.key).text(project.name + '（' + project.key + '）').appendTo($project);
                if (project.key === defaults.projectKey) {
                    matched = true;
                }
            });
            if (matched) {
                $project.val(defaults.projectKey);
            }
            loadTypes($project.val());
        }).fail(function (xhr) {
            $project.empty().append('<option value="">加载失败</option>');
            $alert.text(errorText(xhr, '无法连接 Jira，请检查应用链接配置')).show();
        });

        api('/priorities').done(function (priorities) {
            $.each(priorities, function (i, priority) {
                $('<option/>').val(priority.id).text(priority.name).appendTo($priority);
            });
        });

        $create.on('click', function () {
            var summary = $.trim($summary.val());
            if (!$project.val() || !$type.val() || !summary) {
                $alert.text('请填写项目、任务类型和标题').show();
                return;
            }
            $alert.hide();
            $create.prop('disabled', true);
            $status.text('创建中…');

            api('/issues', {
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    projectKey: $project.val(),
                    issueTypeName: $type.val(),
                    summary: summary,
                    description: $description.val(),
                    dueDate: $due.val(),
                    priorityId: $priority.val(),
                    assigneeName: assignee ? assignee.name : '',
                    labels: defaults.labels ? $.map(defaults.labels.split(','), $.trim) : [],
                    pageId: pageId
                })
            }).done(function (issue) {
                $status.empty().append('已创建 <a href="' + esc(issue.url)
                    + '" target="_blank" rel="noreferrer">' + esc(issue.key) + '</a>');
                $summary.val(defaults.summaryPrefix || '');
                $description.val('');
                $due.val('');
                loadRelated($macro, pageId);
            }).fail(function (xhr) {
                $alert.text(errorText(xhr, '创建失败')).show();
                $status.empty();
            }).always(function () {
                $create.prop('disabled', false);
            });
        });

        loadRelated($macro, pageId);
    }

    AJS.toInit(function () {
        $('.jira-task-macro').each(function () {
            init($(this));
        });
    });
})(AJS.$);
